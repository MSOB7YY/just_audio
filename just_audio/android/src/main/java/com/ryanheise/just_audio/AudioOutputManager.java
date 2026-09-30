// by claude
package com.ryanheise.just_audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;

import io.flutter.plugin.common.BinaryMessenger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Output device selection and bit-perfect USB playback, shared by every player: Android 14's bit-perfect mixer, or
/// any Android through [UsbDirectManager] driving the dac itself. A bit-perfect stream is only ever written as
/// integer PCM, so any integer output config reaching [#prepareOutput] belongs to the bit-perfect path.
@UnstableApi
final class AudioOutputManager {

  private static final String REASON_ACTIVE = "active";
  private static final String REASON_READY = "ready";
  private static final String REASON_DISABLED = "disabled";
  private static final String REASON_UNSUPPORTED_ANDROID = "unsupported_android";
  private static final String REASON_NO_DEVICE = "no_device";
  private static final String REASON_UNSUPPORTED_FORMAT = "unsupported_format";

  private static final int USB_DIRECT_DEVICE_ID = -1;

  @Nullable private static AudioOutputManager instance;

  static AudioOutputManager get(Context context) {
    if (instance == null) instance = new AudioOutputManager(context.getApplicationContext());
    return instance;
  }

  private final AudioManager audioManager;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final UsbDirectManager usbDirect;
  private final SystemVolumeFollower systemVolume;
  private final AudioSignalPath signalPath = new AudioSignalPath();
  private int mixerSampleRate;
  private final android.media.AudioAttributes mediaAttributes =
      new android.media.AudioAttributes.Builder()
          .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
          .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
          .build();

  @Nullable private BinaryMessenger attachedMessenger;
  @Nullable private BetterEventChannel eventChannel;
  private volatile boolean bitPerfectEnabled;
  @Nullable private volatile AudioDeviceInfo preferredDevice;

  private final Map<Integer, List<BitPerfectFormat>> bitPerfectFormatsCache = new HashMap<>();
  @Nullable private BitPerfectFormat appliedFormat;
  @Nullable private AudioDeviceInfo appliedDevice;
  @Nullable private android.media.AudioAttributes appliedAudioAttributes;
  private Map<String, Object> status = statusOf(REASON_DISABLED, null, 0, 0);
  private int usbStreamSampleRate;
  private int usbStreamBitDepth;

  private AudioOutputManager(Context context) {
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    usbDirect = new UsbDirectManager(context, handler, this::onUsbDirectChanged);
    systemVolume = new SystemVolumeFollower(context, audioManager, handler);
    audioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
      @Override
      public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
        onDevicesChanged(addedDevices, true);
      }

      @Override
      public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
        onDevicesChanged(removedDevices, false);
      }
    }, handler);
  }

  void attach(BinaryMessenger messenger) {
    attachedMessenger = messenger;
    eventChannel = new BetterEventChannel(messenger, "com.ryanheise.just_audio.output");
  }

  void detach(BinaryMessenger messenger) {
    if (messenger != attachedMessenger) return;
    attachedMessenger = null;
    eventChannel = null;
    usbDirect.setEnabled(false);
  }

  @Nullable
  AudioDeviceInfo getPreferredDevice() {
    return preferredDevice;
  }

  boolean isBitPerfectEnabled() {
    return bitPerfectEnabled;
  }

  @Nullable
  UsbAudioDevice getUsbDevice() {
    return usbDirect.getDevice();
  }

  void setUsbDirectEnabled(boolean enabled) {
    usbDirect.setEnabled(enabled);
  }

  void onUsbDeviceHandedOver(Intent intent) {
    usbDirect.onHandedOver(intent);
  }

  int getUsbTargetSampleRate(int sampleRate, int channels) {
    @Nullable final UsbAudioDevice device = usbDirect.getDevice();
    if (device == null) return sampleRate;
    final int targetRate = device.getTargetSampleRate(sampleRate, channels);
    return targetRate == 0 ? sampleRate : targetRate;
  }

  void setPreferredDevice(@Nullable Integer deviceId) {
    final AudioDeviceInfo device = deviceId == null ? null : findOutputDevice(deviceId);
    if (device == preferredDevice) return;
    preferredDevice = device;
    for (AudioPlayer player : MainMethodCallHandler.allPlayers()) {
      player.setPreferredAudioDevice(device);
    }
    if (bitPerfectEnabled) {
      synchronized (this) {
        refreshIdleStatusLocked();
      }
      reloadPlayers();
    }
    broadcast(true);
  }

  void setBitPerfectEnabled(boolean enabled) {
    if (enabled == bitPerfectEnabled) return;
    bitPerfectEnabled = enabled;
    synchronized (this) {
      if (!enabled) clearAppliedMixerAttributes();
      refreshIdleStatusLocked();
    }
    for (AudioPlayer player : MainMethodCallHandler.allPlayers()) {
      player.rebuildForAudioOutput();
    }
    broadcast(true);
  }

  List<Map<String, Object>> getOutputDevices() {
    final AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
    @Nullable final UsbAudioDevice usbDevice = usbDirect.getDevice();
    @Nullable final AudioDeviceInfo routed = usbDevice == null ? getRoutedDevice() : null;
    final List<Map<String, Object>> list = new ArrayList<>();
    if (usbDevice != null) list.add(usbDeviceEntryOf(usbDevice));
    for (AudioDeviceInfo device : devices) {
      final int type = device.getType();
      if (!isSelectableOutput(type)) continue;
      // -- android keeps listing the claimed dac as connected, only its kernel driver is gone
      if (usbDevice != null && isUsbType(type)) continue;
      final Map<String, Object> map = new HashMap<>();
      map.put("id", device.getId());
      map.put("type", device.getType());
      map.put("name", device.getProductName() == null ? null : device.getProductName().toString());
      map.put("address", Build.VERSION.SDK_INT >= 28 ? device.getAddress() : null);
      map.put("isRouted", routed != null && routed.getId() == device.getId());
      int maxSampleRate = 0;
      int maxBitDepth = 0;
      for (BitPerfectFormat format : getBitPerfectFormats(device)) {
        maxSampleRate = Math.max(maxSampleRate, format.sampleRate);
        maxBitDepth = Math.max(maxBitDepth, bitDepthOf(format.encoding));
      }
      map.put("bitPerfect", maxSampleRate > 0);
      map.put("maxSampleRate", maxSampleRate);
      map.put("maxBitDepth", maxBitDepth);
      list.add(map);
    }
    return list;
  }

  synchronized Map<String, Object> getStatus() {
    return status;
  }

  Map<String, Object> getUsbDirectStatus() {
    final Map<String, Object> map = usbDirect.getStatus();
    final boolean isClaimed = usbDirect.getDevice() != null;
    synchronized (this) {
      map.put("sampleRate", isClaimed ? usbStreamSampleRate : 0);
      map.put("bitDepth", isClaimed ? usbStreamBitDepth : 0);
    }
    return map;
  }

  Map<String, Object> getState() {
    final Map<String, Object> state = new HashMap<>();
    state.put("devices", getOutputDevices());
    state.put("preferredDeviceId", preferredDevice == null ? null : preferredDevice.getId());
    state.put("bitPerfect", getStatus());
    state.put("usbDirect", getUsbDirectStatus());
    state.put("signalPath", signalPath.toMap());
    return state;
  }

  void onSourceFormat(Format format) {
    signalPath.setSource(format);
    broadcast(false);
  }

  void onDecoderInitialized(String decoderName) {
    signalPath.setDecoder(decoderName);
    broadcast(false);
  }

  void onSinkInput(Format decodedFormat, boolean isBitPerfect) {
    signalPath.setSinkInput(decodedFormat, isBitPerfect);
  }

  /// The integer encoding a PCM `format` reaches the device in untouched, or [C#ENCODING_INVALID].
  @C.PcmEncoding
  int getBitPerfectPcmEncoding(Format format) {
    if (!bitPerfectEnabled) return C.ENCODING_INVALID;
    @Nullable final UsbAudioDevice usbDevice = usbDirect.getDevice();
    if (usbDevice != null) return usbDevice.getLosslessEncoding(format.pcmEncoding, format.sampleRate, format.channelCount);
    if (Build.VERSION.SDK_INT < 34) return C.ENCODING_INVALID;
    @Nullable final AudioDeviceInfo device = getTargetDevice();
    if (device == null) return C.ENCODING_INVALID;
    final List<BitPerfectFormat> formats = getBitPerfectFormats(device);
    if (formats.isEmpty()) return C.ENCODING_INVALID;
    final int channelMask = Util.getAudioTrackChannelConfig(format.channelCount);
    for (int encoding : losslessEncodingsFor(format.pcmEncoding)) {
      if (findFormat(formats, encoding, format.sampleRate, channelMask) != null) return encoding;
    }
    return C.ENCODING_INVALID;
  }

  @Nullable
  UsbDirectAudioOutput openUsbOutput(OutputConfig config) {
    @Nullable final UsbAudioDevice device = usbDirect.getDevice();
    if (device == null) return null;
    @Nullable final UsbDirectAudioOutput output = UsbDirectAudioOutput.open(usbDirect, device, config);
    if (output == null) return null;
    final int bitDepth = output.getBitDepth();
    final int channels = output.getChannels();
    final boolean isDithered = !output.isLossless();
    signalPath.setUsbOutput(device.name, config.sampleRate, channels, bitDepth, isDithered, device.hasHardwareVolume());
    synchronized (this) {
      usbStreamSampleRate = config.sampleRate;
      usbStreamBitDepth = bitDepth;
      if (!bitPerfectEnabled) {
        status = statusOf(REASON_DISABLED, null, 0, 0);
      } else if (output.isLossless()) {
        status = statusOf(REASON_ACTIVE, USB_DIRECT_DEVICE_ID, device.name, config.sampleRate, bitDepth);
      } else {
        status = statusOf(REASON_UNSUPPORTED_FORMAT, USB_DIRECT_DEVICE_ID, device.name, 0, 0);
      }
    }
    broadcast(false);
    return output;
  }

  /// Called on the playback thread right before an output is created for `config`.
  void prepareOutput(OutputConfig config) {
    @Nullable final AudioDeviceInfo device = getTargetDevice();
    final boolean isBitPerfectMixer;
    synchronized (this) {
      isBitPerfectMixer = prepareOutputLocked(config, device);
    }
    @Nullable final CharSequence productName = device == null ? null : device.getProductName();
    @Nullable final String deviceName = productName == null ? null : productName.toString();
    final int channels = Integer.bitCount(config.channelMask);
    final int androidMixerSampleRate = isBitPerfectMixer ? 0 : getMixerSampleRate();
    signalPath.setAndroidOutput(isBitPerfectMixer, deviceName, config.sampleRate, channels, config.encoding, androidMixerSampleRate);
    broadcast(false);
  }

  /// true when the bit-perfect mixer plays [config].
  private boolean prepareOutputLocked(OutputConfig config, @Nullable AudioDeviceInfo device) {
    if (!bitPerfectEnabled) {
      clearAppliedMixerAttributes();
      status = statusOf(REASON_DISABLED, null, 0, 0);
      return false;
    }
    if (Build.VERSION.SDK_INT < 34) {
      status = statusOf(REASON_UNSUPPORTED_ANDROID, null, 0, 0);
      return false;
    }
    final List<BitPerfectFormat> formats = device == null ? Collections.emptyList() : getBitPerfectFormats(device);
    if (device == null || formats.isEmpty()) {
      clearAppliedMixerAttributes();
      status = statusOf(REASON_NO_DEVICE, device, 0, 0);
      return false;
    }
    @Nullable final BitPerfectFormat match = isIntegerPcm(config.encoding)
        ? findFormat(formats, config.encoding, config.sampleRate, config.channelMask)
        : null;
    if (match == null) {
      clearAppliedMixerAttributes();
      status = statusOf(REASON_UNSUPPORTED_FORMAT, device, 0, 0);
      return false;
    }
    final android.media.AudioAttributes audioAttributes = config.audioAttributes.getPlatformAudioAttributes();
    if (match != appliedFormat || appliedDevice == null || appliedDevice.getId() != device.getId()) {
      clearAppliedMixerAttributes();
      if (match.setPreferred(audioManager, audioAttributes, device)) {
        appliedFormat = match;
        appliedDevice = device;
        appliedAudioAttributes = audioAttributes;
      }
    }
    status = appliedFormat == null
        ? statusOf(REASON_UNSUPPORTED_FORMAT, device, 0, 0)
        : statusOf(REASON_ACTIVE, device, config.sampleRate, bitDepthOf(config.encoding));
    return appliedFormat != null;
  }

  /// what bit-perfect can do on the current output before anything plays, an output in use keeps reporting its own stream.
  private void refreshIdleStatusLocked() {
    if (!bitPerfectEnabled) {
      status = statusOf(REASON_DISABLED, null, 0, 0);
      return;
    }
    @Nullable final UsbAudioDevice usbDevice = usbDirect.getDevice();
    if (usbDevice != null) {
      if (!usbDevice.hasOpenStreams()) {
        final int maxSampleRate = usbDevice.getMaxSampleRate();
        final int maxBitDepth = usbDevice.getMaxBitDepth();
        status = statusOf(REASON_READY, USB_DIRECT_DEVICE_ID, usbDevice.name, maxSampleRate, maxBitDepth);
      }
      return;
    }
    if (Build.VERSION.SDK_INT < 34) {
      status = statusOf(REASON_UNSUPPORTED_ANDROID, null, 0, 0);
      return;
    }
    @Nullable final AudioDeviceInfo device = getTargetDevice();
    final List<BitPerfectFormat> formats = device == null ? Collections.emptyList() : getBitPerfectFormats(device);
    if (device == null || formats.isEmpty()) {
      status = statusOf(REASON_NO_DEVICE, device, 0, 0);
      return;
    }
    if (appliedFormat != null && appliedDevice != null && appliedDevice.getId() == device.getId()) return;
    int maxSampleRate = 0;
    int maxBitDepth = 0;
    for (BitPerfectFormat format : formats) {
      maxSampleRate = Math.max(maxSampleRate, format.sampleRate);
      maxBitDepth = Math.max(maxBitDepth, bitDepthOf(format.encoding));
    }
    status = statusOf(REASON_READY, device, maxSampleRate, maxBitDepth);
  }

  /// android's primary output rate, what its mixer resamples everything to.
  private int getMixerSampleRate() {
    if (mixerSampleRate == 0) {
      @Nullable final String property = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
      mixerSampleRate = property == null ? 0 : Integer.parseInt(property);
    }
    return mixerSampleRate;
  }

  private void clearAppliedMixerAttributes() {
    if (appliedFormat == null) return;
    appliedFormat.clearPreferred(audioManager, appliedAudioAttributes, appliedDevice);
    appliedFormat = null;
    appliedDevice = null;
    appliedAudioAttributes = null;
  }

  void releaseIfUnused() {
    if (!MainMethodCallHandler.allPlayers().isEmpty()) return;
    synchronized (this) {
      clearAppliedMixerAttributes();
    }
  }

  /// a released dac pauses like unplugged headphones would, the players would otherwise carry on out of the phone's speaker.
  private void onUsbDirectChanged(boolean didClaimChange) {
    if (didClaimChange) {
      @Nullable final UsbAudioDevice usbDevice = usbDirect.getDevice();
      systemVolume.follow(usbDevice);
      synchronized (this) {
        refreshIdleStatusLocked();
      }
      for (AudioPlayer player : MainMethodCallHandler.allPlayers()) {
        if (usbDevice == null) player.pauseForAudioOutput();
        player.reloadForAudioOutput();
      }
    }
    broadcast(true);
  }

  private void onDevicesChanged(AudioDeviceInfo[] devices, boolean added) {
    boolean affectsBitPerfect = false;
    synchronized (this) {
      for (AudioDeviceInfo device : devices) {
        if (added) {
          bitPerfectFormatsCache.remove(device.getId());
          if (!getBitPerfectFormats(device).isEmpty()) affectsBitPerfect = true;
        } else {
          final List<BitPerfectFormat> removed = bitPerfectFormatsCache.remove(device.getId());
          if (removed != null && !removed.isEmpty()) affectsBitPerfect = true;
          if (appliedDevice != null && appliedDevice.getId() == device.getId()) {
            appliedFormat = null;
            appliedDevice = null;
            appliedAudioAttributes = null;
            affectsBitPerfect = true;
          }
        }
      }
    }
    if (!added && preferredDevice != null) {
      for (AudioDeviceInfo device : devices) {
        if (device.getId() == preferredDevice.getId()) {
          preferredDevice = null;
          for (AudioPlayer player : MainMethodCallHandler.allPlayers()) {
            player.setPreferredAudioDevice(null);
          }
          break;
        }
      }
    }
    if (bitPerfectEnabled && affectsBitPerfect) {
      synchronized (this) {
        refreshIdleStatusLocked();
      }
      if (usbDirect.getDevice() == null) reloadPlayers();
    }
    broadcast(true);
  }

  private void reloadPlayers() {
    for (AudioPlayer player : MainMethodCallHandler.allPlayers()) {
      player.reloadForAudioOutput();
    }
  }

  private void broadcast(boolean includeDevices) {
    handler.post(() -> {
      if (eventChannel == null) return;
      final Map<String, Object> event = new HashMap<>();
      if (includeDevices) {
        event.put("devices", getOutputDevices());
        event.put("preferredDeviceId", preferredDevice == null ? null : preferredDevice.getId());
      }
      event.put("bitPerfect", getStatus());
      event.put("usbDirect", getUsbDirectStatus());
      event.put("signalPath", signalPath.toMap());
      eventChannel.success(event);
    });
  }

  @Nullable
  private AudioDeviceInfo getTargetDevice() {
    @Nullable final AudioDeviceInfo preferred = preferredDevice;
    return preferred != null ? preferred : getRoutedDevice();
  }

  @Nullable
  private AudioDeviceInfo getRoutedDevice() {
    if (Build.VERSION.SDK_INT < 33) return null;
    final List<AudioDeviceInfo> routed = audioManager.getAudioDevicesForAttributes(mediaAttributes);
    return routed.isEmpty() ? null : routed.get(0);
  }

  @Nullable
  private AudioDeviceInfo findOutputDevice(int deviceId) {
    for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
      if (device.getId() == deviceId) return device;
    }
    return null;
  }

  private synchronized List<BitPerfectFormat> getBitPerfectFormats(AudioDeviceInfo device) {
    List<BitPerfectFormat> formats = bitPerfectFormatsCache.get(device.getId());
    if (formats == null) {
      formats = Build.VERSION.SDK_INT >= 34 ? BitPerfectFormat.listFor(audioManager, device) : Collections.emptyList();
      bitPerfectFormatsCache.put(device.getId(), formats);
    }
    return formats;
  }

  @Nullable
  private static BitPerfectFormat findFormat(List<BitPerfectFormat> formats, int encoding, int sampleRate, int channelMask) {
    for (BitPerfectFormat format : formats) {
      if (format.encoding == encoding && format.sampleRate == sampleRate && format.channelMask == channelMask) return format;
    }
    return null;
  }

  /// narrowest first so integer sources keep their own depth, float goes into the widest container.
  private static int[] losslessEncodingsFor(@C.PcmEncoding int encoding) {
    switch (encoding) {
      case C.ENCODING_PCM_16BIT:
        return new int[] {C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT};
      case C.ENCODING_PCM_24BIT:
        return new int[] {C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT};
      case C.ENCODING_PCM_32BIT:
        return new int[] {C.ENCODING_PCM_32BIT};
      case C.ENCODING_PCM_FLOAT:
        return new int[] {C.ENCODING_PCM_32BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_16BIT};
      default:
        return new int[0];
    }
  }

  private static boolean isIntegerPcm(int encoding) {
    return encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_24BIT || encoding == C.ENCODING_PCM_32BIT;
  }

  private static int bitDepthOf(int encoding) {
    switch (encoding) {
      case AudioFormat.ENCODING_PCM_16BIT:
        return 16;
      case AudioFormat.ENCODING_PCM_24BIT_PACKED:
        return 24;
      case AudioFormat.ENCODING_PCM_32BIT:
      case AudioFormat.ENCODING_PCM_FLOAT:
        return 32;
      default:
        return 0;
    }
  }

  private static boolean isUsbType(int type) {
    return type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
  }

  private static boolean isSelectableOutput(int type) {
    switch (type) {
      case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
      case AudioDeviceInfo.TYPE_WIRED_HEADSET:
      case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
      case AudioDeviceInfo.TYPE_LINE_ANALOG:
      case AudioDeviceInfo.TYPE_LINE_DIGITAL:
      case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
      case AudioDeviceInfo.TYPE_HDMI:
      case AudioDeviceInfo.TYPE_HDMI_ARC:
      case AudioDeviceInfo.TYPE_HDMI_EARC:
      case AudioDeviceInfo.TYPE_USB_DEVICE:
      case AudioDeviceInfo.TYPE_USB_ACCESSORY:
      case AudioDeviceInfo.TYPE_USB_HEADSET:
      case AudioDeviceInfo.TYPE_DOCK:
      case AudioDeviceInfo.TYPE_DOCK_ANALOG:
      case AudioDeviceInfo.TYPE_AUX_LINE:
      case AudioDeviceInfo.TYPE_HEARING_AID:
      case AudioDeviceInfo.TYPE_BLE_HEADSET:
      case AudioDeviceInfo.TYPE_BLE_SPEAKER:
      case AudioDeviceInfo.TYPE_BLE_BROADCAST:
        return true;
      default:
        return false;
    }
  }

  private static Map<String, Object> statusOf(String reason, @Nullable AudioDeviceInfo device, int sampleRate, int bitDepth) {
    @Nullable final CharSequence productName = device == null ? null : device.getProductName();
    @Nullable final String deviceName = productName == null ? null : productName.toString();
    @Nullable final Integer deviceId = device == null ? null : device.getId();
    return statusOf(reason, deviceId, deviceName, sampleRate, bitDepth);
  }

  private static Map<String, Object> statusOf(String reason, @Nullable Integer deviceId, @Nullable String deviceName, int sampleRate,
      int bitDepth) {
    final boolean isUsbDirect = deviceId != null && deviceId == USB_DIRECT_DEVICE_ID;
    final boolean isSupported = Build.VERSION.SDK_INT >= 34 || isUsbDirect;
    final Map<String, Object> map = new HashMap<>();
    map.put("supported", isSupported);
    map.put("reason", reason);
    map.put("deviceId", deviceId);
    map.put("deviceName", deviceName);
    map.put("sampleRate", sampleRate);
    map.put("bitDepth", bitDepth);
    return map;
  }

  private static Map<String, Object> usbDeviceEntryOf(UsbAudioDevice device) {
    final Map<String, Object> map = new HashMap<>();
    map.put("id", USB_DIRECT_DEVICE_ID);
    map.put("type", AudioDeviceInfo.TYPE_USB_DEVICE);
    map.put("name", device.name);
    map.put("address", null);
    map.put("isRouted", true);
    map.put("bitPerfect", true);
    map.put("maxSampleRate", device.getMaxSampleRate());
    map.put("maxBitDepth", device.getMaxBitDepth());
    map.put("usbDirect", true);
    return map;
  }

  @RequiresApi(34)
  private static final class BitPerfectFormat {
    final AudioMixerAttributes attributes;
    final int encoding;
    final int sampleRate;
    final int channelMask;

    private BitPerfectFormat(AudioMixerAttributes attributes) {
      final AudioFormat format = attributes.getFormat();
      this.attributes = attributes;
      this.encoding = format.getEncoding();
      this.sampleRate = format.getSampleRate();
      this.channelMask = format.getChannelMask();
    }

    static List<BitPerfectFormat> listFor(AudioManager audioManager, AudioDeviceInfo device) {
      final List<BitPerfectFormat> list = new ArrayList<>();
      for (AudioMixerAttributes attributes : audioManager.getSupportedMixerAttributes(device)) {
        if (attributes.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
          list.add(new BitPerfectFormat(attributes));
        }
      }
      return list;
    }

    boolean setPreferred(AudioManager audioManager, android.media.AudioAttributes audioAttributes, AudioDeviceInfo device) {
      try {
        return audioManager.setPreferredMixerAttributes(audioAttributes, device, attributes);
      } catch (RuntimeException e) {
        return false;
      }
    }

    void clearPreferred(AudioManager audioManager, android.media.AudioAttributes audioAttributes, AudioDeviceInfo device) {
      try {
        audioManager.clearPreferredMixerAttributes(audioAttributes, device);
      } catch (RuntimeException ignored) {
      }
    }
  }
}
