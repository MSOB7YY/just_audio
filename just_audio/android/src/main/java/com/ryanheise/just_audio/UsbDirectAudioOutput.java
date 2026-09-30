// by claude
package com.ryanheise.just_audio;

import android.media.AudioDeviceInfo;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// plays through a usb dac claimed by [UsbDirectManager], bypassing android's audio stack. integer PCM only ever
/// arrives here from the bit-perfect path and is copied untouched, float PCM is dithered to the dac's resolution.
@UnstableApi
final class UsbDirectAudioOutput implements AudioOutput {

  static final long RING_DURATION_US = 250_000;

  private final UsbDirectManager manager;
  private final UsbAudioDevice device;
  private final UsbAudioStream stream;
  private final UsbAudioDescriptors.Format format;
  private final OutputConfig config;
  private final boolean isFloat;
  private final List<Listener> listeners = new ArrayList<>();

  private boolean isPlaying;
  private boolean didReportAdvancing;
  private boolean didFail;

  private UsbDirectAudioOutput(UsbDirectManager manager, UsbAudioDevice device, UsbAudioStream stream, UsbAudioDescriptors.Format format,
      OutputConfig config, boolean isFloat) {
    this.manager = manager;
    this.device = device;
    this.stream = stream;
    this.format = format;
    this.config = config;
    this.isFloat = isFloat;
  }

  @Nullable
  static UsbDirectAudioOutput open(UsbDirectManager manager, UsbAudioDevice device, OutputConfig config) {
    final boolean isFloat = config.encoding == C.ENCODING_PCM_FLOAT;
    final int channels = Integer.bitCount(config.channelMask);
    @Nullable final UsbAudioDescriptors.Format format;
    if (isFloat) {
      format = device.findBestFormat(config.sampleRate, channels);
    } else {
      format = device.findFormatForEncoding(config.encoding, config.sampleRate, channels);
    }
    if (format == null || !device.acquireStream()) return null;
    final int fileDescriptor = device.getFileDescriptor();
    final int bytesPerSample = UsbAudioDevice.bytesPerSample(config.encoding);
    @Nullable final UsbAudioStream stream = UsbAudioStream.create(fileDescriptor, format, device.isHighSpeed, config.sampleRate, isFloat, channels,
        bytesPerSample);
    if (stream == null) {
      device.releaseStream(null);
      return null;
    }
    return new UsbDirectAudioOutput(manager, device, stream, format, config, isFloat);
  }

  boolean isLossless() {
    return !isFloat;
  }

  int getBitDepth() {
    return format.bitResolution;
  }

  int getChannels() {
    return format.channels;
  }

  /// the dac switches format and clock here rather than on creation, so a paused output never retunes it under another one.
  @Override
  public void play() {
    if (!device.configure(format, config.sampleRate)) {
      fail();
      return;
    }
    isPlaying = true;
    stream.setPlaying(true);
  }

  @Override
  public void pause() {
    isPlaying = false;
    stream.setPlaying(false);
  }

  @Override
  public boolean write(ByteBuffer buffer, int encodedAccessUnitCount, long presentationTimeUs) {
    if (!buffer.hasRemaining()) return true;
    final int written = stream.write(buffer);
    buffer.position(buffer.position() + written);
    return !buffer.hasRemaining();
  }

  @Override
  public void flush() {}

  @Override
  public void stop() {
    stream.endOfStream();
  }

  @Override
  public void release() {
    stream.release();
    device.releaseStream(stream);
    for (Listener listener : listeners) listener.onReleased();
  }

  @Override
  public void setVolume(float volume) {
    device.setVolume(stream, volume, isFloat);
  }

  @Override
  public boolean isOffloadedPlayback() {
    return false;
  }

  @Override
  public int getAudioSessionId() {
    return config.audioSessionId;
  }

  @Override
  public int getSampleRate() {
    return config.sampleRate;
  }

  @Override
  public long getBufferSizeInFrames() {
    return Util.durationUsToSampleCount(RING_DURATION_US, config.sampleRate);
  }

  @Override
  public long getPositionUs() {
    final long playedFrames = stream.getPlayedFrames();
    if (isPlaying && playedFrames > 0 && !didReportAdvancing) {
      didReportAdvancing = true;
      final long now = System.currentTimeMillis();
      for (Listener listener : listeners) listener.onPositionAdvancing(now);
    }
    return Util.sampleCountToDurationUs(playedFrames, config.sampleRate);
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return PlaybackParameters.DEFAULT;
  }

  /// a failed dac is dropped right away, the sink then waits for the players to reload onto android's own output.
  @Override
  public boolean isStalled() {
    if (!didFail && stream.getErrorCode() != 0) fail();
    return didFail;
  }

  private void fail() {
    didFail = true;
    manager.onDeviceFailed(device);
  }

  @Override
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParams) {}

  @Override
  public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) {}

  @Override
  public void setOffloadEndOfStream() {}

  @Override
  public void attachAuxEffect(int effectId) {}

  @Override
  public void setAuxEffectSendLevel(float level) {}

  @Override
  public void setPreferredDevice(@Nullable AudioDeviceInfo preferredDevice) {}
}
