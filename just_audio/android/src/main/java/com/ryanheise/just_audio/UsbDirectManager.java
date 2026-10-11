// by claude
package com.ryanheise.just_audio;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// claims a usb dac for [UsbDirectAudioOutput] while usb direct is on, following permission, plugging and stream failures.
/// the claimed device is read from playback threads, everything else runs on the main thread.
///
/// an app declaring a USB_DEVICE_ATTACHED activity gets plugged dacs handed over by the system, with its own
/// dialog and the permission already granted ([#onHandedOver]), so a dac plugged in isn't asked for twice.
///
/// detaching the kernel driver while android still streams to the dac locks the usb stack on snapdragon phones (the dsp
/// offload session is torn down under it, snapshots#121), so a claim pauses the players, waits for every playback to stop
/// plus audioflinger's standby, and opens the dac off the main thread.
final class UsbDirectManager {

  private static final String TAG = "UsbDirectManager";

  interface Listener {
    /// the players are paused so android lets go of the dac.
    void onUsbDirectClaiming();

    /// [resume] plays again what [#onUsbDirectClaiming] paused, a dac that went away keeps them paused.
    void onUsbDirectClaimEnded(boolean resume);

    void onUsbDirectChanged(boolean didClaimChange);
  }

  private static final String STATE_OFF = "off";
  private static final String STATE_NO_DEVICE = "no_device";
  private static final String STATE_AWAITING_PERMISSION = "awaiting_permission";
  private static final String STATE_PERMISSION_DENIED = "permission_denied";
  private static final String STATE_WAITING_PLAYBACK = "waiting_playback";
  private static final String STATE_CLAIMING = "claiming";
  private static final String STATE_UNSUPPORTED_DEVICE = "unsupported_device";
  private static final String STATE_FAILED = "failed";
  private static final String STATE_ACTIVE = "active";

  private static final String ACTION_USB_PERMISSION = "com.ryanheise.just_audio.USB_PERMISSION";

  // -- audioflinger keeps the hal stream open this long after the last track stops (kDefaultStandbyTimeInNsecs), plus margin
  private static final long STANDBY_GRACE_MS = 3500;

  private final Context context;
  private final UsbManager usbManager;
  private final Handler handler;
  private final Listener listener;
  private final BroadcastReceiver receiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      @Nullable final UsbDevice usbDevice = getUsbDeviceExtra(intent);
      @Nullable final String action = intent.getAction();
      if (usbDevice == null || action == null) return;
      switch (action) {
        case UsbManager.ACTION_USB_DEVICE_ATTACHED:
          onAttached(usbDevice);
          break;
        case UsbManager.ACTION_USB_DEVICE_DETACHED:
          onDetached(usbDevice);
          break;
        case ACTION_USB_PERMISSION:
          final boolean isGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
          onPermissionResult(usbDevice, isGranted);
          break;
      }
    }
  };
  private final Runnable openPendingDevice = this::continueClaim;

  private boolean isEnabled;
  @Nullable private volatile UsbAudioDevice device;
  private String state = STATE_OFF;
  @Nullable private String stateDeviceName;

  @Nullable private UsbDevice pendingDevice;
  private int claimGeneration;
  private boolean isPlaybackActive;
  private long lastActivePlaybackUptime;

  UsbDirectManager(Context context, AudioManager audioManager, Handler handler, Listener listener) {
    this.context = context;
    this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    this.handler = handler;
    this.listener = listener;
    if (Build.VERSION.SDK_INT >= 26) followPlayback(audioManager);
  }

  @RequiresApi(26)
  private void followPlayback(AudioManager audioManager) {
    final boolean isActiveNow = !audioManager.getActivePlaybackConfigurations().isEmpty();
    onPlaybackChanged(isActiveNow);
    audioManager.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
      @Override
      public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        onPlaybackChanged(!configs.isEmpty());
      }
    }, handler);
  }

  private void onPlaybackChanged(boolean isActive) {
    if (isActive || isPlaybackActive) lastActivePlaybackUptime = SystemClock.uptimeMillis();
    isPlaybackActive = isActive;
    if (!isActive && pendingDevice != null && STATE_WAITING_PLAYBACK.equals(state)) continueClaim();
  }

  static boolean isDeviceAttachedIntent(Intent intent) {
    return UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction());
  }

  /// the app's USB_DEVICE_ATTACHED activity may be toggled at runtime, so it's checked per attach.
  private boolean isAttachHandledBySystem() {
    final Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    intent.setPackage(context.getPackageName());
    final List<ResolveInfo> handlers = context.getPackageManager().queryIntentActivities(intent, 0);
    return !handlers.isEmpty();
  }

  @Nullable
  UsbAudioDevice getDevice() {
    return device;
  }

  void setEnabled(boolean enabled) {
    if (enabled == isEnabled) return;
    isEnabled = enabled;
    if (enabled) {
      registerReceiver();
      claimAttachedDac();
      listener.onUsbDirectChanged(false);
      return;
    }
    context.unregisterReceiver(receiver);
    cancelClaim(true);
    final boolean didRelease = releaseDevice();
    setState(STATE_OFF, null);
    listener.onUsbDirectChanged(didRelease);
  }

  /// called from the playback thread when a stream or the dac's controls failed, the device stops being handed out right away.
  void onDeviceFailed(UsbAudioDevice failed) {
    synchronized (this) {
      if (device != failed) return;
      device = null;
    }
    handler.post(() -> {
      failed.close();
      if (isEnabled) setState(STATE_FAILED, failed.name);
      listener.onUsbDirectChanged(true);
    });
  }

  Map<String, Object> getStatus() {
    @Nullable final UsbAudioDevice current = device;
    final int maxSampleRate = current == null ? 0 : current.getMaxSampleRate();
    final int maxBitDepth = current == null ? 0 : current.getMaxBitDepth();
    final boolean hasHardwareVolume = current != null && current.hasHardwareVolume();
    final Map<String, Object> map = new HashMap<>();
    map.put("state", state);
    map.put("deviceName", stateDeviceName);
    map.put("maxSampleRate", maxSampleRate);
    map.put("maxBitDepth", maxBitDepth);
    map.put("hardwareVolume", hasHardwareVolume);
    return map;
  }

  /// the activity got the dac from the system, permission included.
  void onHandedOver(Intent intent) {
    @Nullable final UsbDevice usbDevice = getUsbDeviceExtra(intent);
    if (!isEnabled || isBusy() || usbDevice == null || !UsbAudioDevice.isAudioOutput(usbDevice)) return;
    claim(usbDevice);
  }

  private boolean isBusy() {
    return device != null || pendingDevice != null;
  }

  private void onAttached(UsbDevice usbDevice) {
    if (isBusy() || !UsbAudioDevice.isAudioOutput(usbDevice)) return;
    if (!usbManager.hasPermission(usbDevice) && isAttachHandledBySystem()) {
      setState(STATE_AWAITING_PERMISSION, UsbAudioDevice.displayNameOf(usbDevice));
      listener.onUsbDirectChanged(false);
      return;
    }
    claim(usbDevice);
  }

  private void onDetached(UsbDevice usbDevice) {
    if (!UsbAudioDevice.isAudioOutput(usbDevice)) return;
    @Nullable final UsbAudioDevice current = device;
    if (current != null && !isSameDevice(current.device, usbDevice)) return;
    if (pendingDevice != null && !isSameDevice(pendingDevice, usbDevice)) return;
    cancelClaim(false);
    final boolean didRelease = releaseDevice();
    claimAttachedDac();
    listener.onUsbDirectChanged(didRelease);
  }

  private void onPermissionResult(UsbDevice usbDevice, boolean isGranted) {
    if (!isEnabled || isBusy()) return;
    if (!isGranted) {
      final String deviceName = UsbAudioDevice.displayNameOf(usbDevice);
      setState(STATE_PERMISSION_DENIED, deviceName);
      listener.onUsbDirectChanged(false);
      return;
    }
    claim(usbDevice);
  }

  private void claimAttachedDac() {
    for (UsbDevice usbDevice : usbManager.getDeviceList().values()) {
      if (UsbAudioDevice.isAudioOutput(usbDevice)) {
        claim(usbDevice);
        return;
      }
    }
    setState(STATE_NO_DEVICE, null);
  }

  private void claim(UsbDevice usbDevice) {
    if (!usbManager.hasPermission(usbDevice)) {
      Log.i(TAG, "requesting permission for " + usbDevice.getDeviceName());
      setState(STATE_AWAITING_PERMISSION, UsbAudioDevice.displayNameOf(usbDevice));
      final PendingIntent permissionIntent = createPermissionIntent();
      usbManager.requestPermission(usbDevice, permissionIntent);
      listener.onUsbDirectChanged(false);
      return;
    }
    pendingDevice = usbDevice;
    claimGeneration++;
    // -- without playback callbacks the last activity is unknown, the claim takes the full standby wait
    if (Build.VERSION.SDK_INT < 26) lastActivePlaybackUptime = SystemClock.uptimeMillis();
    listener.onUsbDirectClaiming();
    continueClaim();
  }

  private void continueClaim() {
    @Nullable final UsbDevice usbDevice = pendingDevice;
    if (usbDevice == null) return;
    final String deviceName = UsbAudioDevice.displayNameOf(usbDevice);
    if (isPlaybackActive) {
      setState(STATE_WAITING_PLAYBACK, deviceName);
      listener.onUsbDirectChanged(false);
      return;
    }
    final long remainingMs = lastActivePlaybackUptime + STANDBY_GRACE_MS - SystemClock.uptimeMillis();
    setState(STATE_CLAIMING, deviceName);
    listener.onUsbDirectChanged(false);
    if (remainingMs > 0) {
      handler.postDelayed(openPendingDevice, remainingMs);
      return;
    }
    final int generation = claimGeneration;
    new Thread(() -> {
      @Nullable final UsbAudioDevice opened = UsbAudioDevice.open(usbManager, usbDevice);
      handler.post(() -> onOpened(generation, usbDevice, opened));
    }, "UsbDirectOpen").start();
  }

  private void onOpened(int generation, UsbDevice usbDevice, @Nullable UsbAudioDevice opened) {
    if (generation != claimGeneration) {
      if (opened != null) new Thread(opened::close, "UsbDirectClose").start();
      return;
    }
    pendingDevice = null;
    if (opened == null) {
      setState(STATE_UNSUPPORTED_DEVICE, UsbAudioDevice.displayNameOf(usbDevice));
      listener.onUsbDirectChanged(false);
      listener.onUsbDirectClaimEnded(true);
      return;
    }
    synchronized (this) {
      device = opened;
    }
    setState(STATE_ACTIVE, opened.name);
    listener.onUsbDirectChanged(true);
    listener.onUsbDirectClaimEnded(true);
  }

  private void cancelClaim(boolean resume) {
    if (pendingDevice == null) return;
    pendingDevice = null;
    claimGeneration++;
    handler.removeCallbacks(openPendingDevice);
    listener.onUsbDirectClaimEnded(resume);
  }

  /// the released device closes once its last stream is gone.
  private boolean releaseDevice() {
    final UsbAudioDevice current;
    synchronized (this) {
      current = device;
      device = null;
    }
    if (current == null) return false;
    current.close();
    return true;
  }

  private void setState(String state, @Nullable String deviceName) {
    this.state = state;
    this.stateDeviceName = deviceName;
  }

  private void registerReceiver() {
    final IntentFilter filter = new IntentFilter();
    filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    filter.addAction(ACTION_USB_PERMISSION);
    if (Build.VERSION.SDK_INT >= 33) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      context.registerReceiver(receiver, filter);
    }
  }

  /// the system fills in the device and the grant, so the intent is mutable, and explicit as android 14 requires for mutable ones.
  private PendingIntent createPermissionIntent() {
    final String packageName = context.getPackageName();
    final Intent intent = new Intent(ACTION_USB_PERMISSION);
    intent.setPackage(packageName);
    final int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
    return PendingIntent.getBroadcast(context, 0, intent, flags);
  }

  private static boolean isSameDevice(UsbDevice first, UsbDevice second) {
    return first.getDeviceName().equals(second.getDeviceName());
  }

  @SuppressWarnings("deprecation")
  @Nullable
  private static UsbDevice getUsbDeviceExtra(Intent intent) {
    if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
    return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
  }
}
