// by claude
package com.ryanheise.just_audio;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;

/// Float PCM sound effects following [SoundEffects], processed natively in place on the output buffer.
///
/// Stays a plain copy until an effect is enabled, and goes back to it once the last one has faded out.
@UnstableApi
final class SoundEffectsAudioProcessor extends BaseAudioProcessor {

  static {
    System.loadLibrary("just_audio_native");
  }

  private long handle;
  private SoundEffects.Config appliedConfig;
  private int configuredSampleRate;
  private int configuredChannelCount;
  private int bytesPerFrame;
  private boolean isIdle = true;

  @Override
  protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    return inputAudioFormat;
  }

  @Override
  protected void onFlush(StreamMetadata streamMetadata) {
    final int sampleRate = inputAudioFormat.sampleRate;
    final int channelCount = inputAudioFormat.channelCount;
    bytesPerFrame = inputAudioFormat.bytesPerFrame;
    if (handle == 0) handle = nativeCreate();
    applyConfig(SoundEffects.get());
    if (sampleRate != configuredSampleRate || channelCount != configuredChannelCount) {
      configuredSampleRate = sampleRate;
      configuredChannelCount = channelCount;
      nativeConfigure(handle, sampleRate, channelCount);
    } else {
      nativeReset(handle);
    }
    isIdle = appliedConfig.enabledMask == 0;
  }

  @Override
  protected void onReset() {
    if (handle != 0) {
      nativeRelease(handle);
      handle = 0;
    }
    appliedConfig = null;
    configuredSampleRate = 0;
    configuredChannelCount = 0;
    isIdle = true;
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    final SoundEffects.Config config = SoundEffects.get();
    if (config != appliedConfig) applyConfig(config);

    final int size = inputBuffer.remaining();
    if (size == 0) return;
    final ByteBuffer output = replaceOutputBuffer(size);
    output.put(inputBuffer);
    output.flip();
    if (isIdle) return;
    isIdle = !nativeProcess(handle, output, size / bytesPerFrame);
  }

  /// an effect being turned off keeps the chain busy until it has faded out.
  private void applyConfig(SoundEffects.Config config) {
    appliedConfig = config;
    nativeSetEffects(handle, config.enabledMask, config.intensities);
    if (config.enabledMask != 0) isIdle = false;
  }

  private static native long nativeCreate();

  private static native void nativeConfigure(long handle, int sampleRate, int channelCount);

  private static native void nativeSetEffects(long handle, int enabledMask, float[] intensities);

  private static native boolean nativeProcess(long handle, ByteBuffer buffer, int frames);

  private static native void nativeReset(long handle);

  private static native void nativeRelease(long handle);
}
