// by claude
package com.ryanheise.just_audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.Nullable;

/// android's media volume as the linear gain it would apply on a usb output, pushed to the claimed dac whenever it
/// changes, so a dac driven directly still follows the volume keys and the system slider.
final class SystemVolumeFollower {

  /// framework broadcasts, sent for every stream volume or mute change since android 2.
  private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
  private static final String ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
  private static final String EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

  /// the framework's default media curve (percent of the index range -> dB), for androids without [AudioManager#getStreamVolumeDb].
  private static final float[][] LEGACY_MEDIA_CURVE = {{1, -58f}, {20, -40f}, {60, -17f}, {100, 0f}};

  private final Context context;
  private final AudioManager audioManager;
  private final Handler handler;
  private final BroadcastReceiver receiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      final int streamType = intent.getIntExtra(EXTRA_STREAM_TYPE, AudioManager.STREAM_MUSIC);
      if (streamType == AudioManager.STREAM_MUSIC) apply();
    }
  };

  @Nullable private UsbAudioDevice device;
  private int usbDeviceType = AudioDeviceInfo.TYPE_USB_DEVICE;

  SystemVolumeFollower(Context context, AudioManager audioManager, Handler handler) {
    this.context = context;
    this.audioManager = audioManager;
    this.handler = handler;
  }

  void follow(@Nullable UsbAudioDevice device) {
    if (device == this.device) return;
    final boolean wasFollowing = this.device != null;
    this.device = device;
    if (device == null) {
      context.unregisterReceiver(receiver);
      return;
    }
    usbDeviceType = findUsbDeviceType();
    if (!wasFollowing) {
      final IntentFilter filter = new IntentFilter();
      filter.addAction(ACTION_VOLUME_CHANGED);
      filter.addAction(ACTION_STREAM_MUTE_CHANGED);
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
      } else {
        context.registerReceiver(receiver, filter, null, handler);
      }
    }
    apply();
  }

  private void apply() {
    @Nullable final UsbAudioDevice current = device;
    if (current == null) return;
    final float gain = getMediaGain();
    current.setSystemGain(gain);
  }

  /// the index is 0 while the stream is muted.
  private float getMediaGain() {
    final int index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    if (index <= 0) return 0f;
    final float db;
    if (Build.VERSION.SDK_INT >= 28) {
      db = audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, usbDeviceType);
    } else {
      final int maxIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      db = legacyMediaDb(index * 100f / maxIndex);
    }
    return (float) Math.pow(10.0, db / 20.0);
  }

  /// android keeps listing the claimed dac, its type picks the same volume curve android would use for it.
  private int findUsbDeviceType() {
    for (AudioDeviceInfo info : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
      final int type = info.getType();
      if (type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE) return type;
    }
    return AudioDeviceInfo.TYPE_USB_DEVICE;
  }

  private static float legacyMediaDb(float percent) {
    float[] previous = LEGACY_MEDIA_CURVE[0];
    if (percent <= previous[0]) return previous[1];
    for (int i = 1; i < LEGACY_MEDIA_CURVE.length; i++) {
      final float[] point = LEGACY_MEDIA_CURVE[i];
      if (percent <= point[0]) {
        final float fraction = (percent - previous[0]) / (point[0] - previous[0]);
        return previous[1] + (point[1] - previous[1]) * fraction;
      }
      previous = point;
    }
    return 0f;
  }
}
