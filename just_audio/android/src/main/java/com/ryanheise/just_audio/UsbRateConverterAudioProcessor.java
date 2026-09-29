// by claude
package com.ryanheise.just_audio;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/// resamples float PCM to a rate the claimed usb dac supports, only active when the track's own rate isn't one.
/// windowed sinc (kaiser, about -80 dB stopband) with its cutoff lowered when downsampling so nothing aliases.
@UnstableApi
final class UsbRateConverterAudioProcessor extends BaseAudioProcessor {

  private static final int ZERO_CROSSINGS = 24;
  private static final int TABLE_STEPS_PER_UNIT = 512;
  private static final double KAISER_BETA = 8.0;
  private static final double CUTOFF_MARGIN = 0.97;

  interface TargetSampleRate {
    /// the rate to convert to, or [sampleRate] itself to stay inactive.
    int get(int sampleRate, int channels);
  }

  private final TargetSampleRate targetSampleRate;

  private int channels;
  private double step;
  private int halfWidth;
  private float[] kernel = new float[0];
  private float kernelScale;

  private float[] history = new float[0];
  private int historyFrames;
  private double position;
  private float[] inputScratch = new float[0];
  private float[] outputScratch = new float[0];

  UsbRateConverterAudioProcessor(TargetSampleRate targetSampleRate) {
    this.targetSampleRate = targetSampleRate;
  }

  @Override
  protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) throw new UnhandledAudioFormatException(inputAudioFormat);
    final int targetRate = targetSampleRate.get(inputAudioFormat.sampleRate, inputAudioFormat.channelCount);
    if (targetRate <= 0 || targetRate == inputAudioFormat.sampleRate) return AudioFormat.NOT_SET;
    return new AudioFormat(targetRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT);
  }

  /// the sink counts frames skipped upstream at the output rate.
  long toOutputFrameCount(long inputFrameCount) {
    if (!isActive()) return inputFrameCount;
    return inputFrameCount * outputAudioFormat.sampleRate / inputAudioFormat.sampleRate;
  }

  @Override
  protected void onFlush(StreamMetadata streamMetadata) {
    if (!isActive()) return;
    channels = inputAudioFormat.channelCount;
    step = (double) inputAudioFormat.sampleRate / outputAudioFormat.sampleRate;
    final double cutoff = Math.min(1.0, 1.0 / step) * CUTOFF_MARGIN;
    halfWidth = (int) Math.ceil(ZERO_CROSSINGS / cutoff);
    buildKernel(cutoff);
    historyFrames = halfWidth;
    ensureHistory(halfWidth * 4);
    Arrays.fill(history, 0, historyFrames * channels, 0f);
    position = halfWidth;
  }

  @Override
  protected void onReset() {
    kernel = new float[0];
    history = new float[0];
    inputScratch = new float[0];
    outputScratch = new float[0];
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    final int samples = inputBuffer.remaining() / 4;
    final int frames = samples / channels;
    if (frames == 0) return;
    if (inputScratch.length < samples) inputScratch = new float[samples];
    inputBuffer.asFloatBuffer().get(inputScratch, 0, samples);
    inputBuffer.position(inputBuffer.limit());
    appendFrames(inputScratch, frames);
    produce();
  }

  @Override
  protected void onQueueEndOfStream() {
    if (inputScratch.length < halfWidth * channels) inputScratch = new float[halfWidth * channels];
    Arrays.fill(inputScratch, 0, halfWidth * channels, 0f);
    appendFrames(inputScratch, halfWidth);
    produce();
  }

  private void appendFrames(float[] source, int frames) {
    ensureHistory(historyFrames + frames);
    System.arraycopy(source, 0, history, historyFrames * channels, frames * channels);
    historyFrames += frames;
  }

  private void produce() {
    final int lastUsable = historyFrames - halfWidth;
    final int outputFrames = position < lastUsable ? (int) Math.ceil((lastUsable - position) / step) : 0;
    if (outputFrames > 0) {
      final int outputSamples = outputFrames * channels;
      if (outputScratch.length < outputSamples) outputScratch = new float[outputSamples];
      for (int f = 0; f < outputFrames; f++) {
        interpolate(position, outputScratch, f * channels);
        position += step;
      }
      final ByteBuffer output = replaceOutputBuffer(outputSamples * 4);
      final FloatBuffer floats = output.asFloatBuffer();
      floats.put(outputScratch, 0, outputSamples);
      output.position(outputSamples * 4);
      output.flip();
    }
    discardConsumed();
  }

  private void interpolate(double at, float[] out, int offset) {
    final int center = (int) at;
    final double fraction = at - center;
    final int first = center - halfWidth + 1;
    for (int ch = 0; ch < channels; ch++) out[offset + ch] = 0f;
    for (int k = first; k <= center + halfWidth; k++) {
      final float weight = kernelAt(Math.abs(k - center - fraction));
      if (weight == 0f) continue;
      final int base = k * channels;
      for (int ch = 0; ch < channels; ch++) out[offset + ch] += history[base + ch] * weight;
    }
  }

  private float kernelAt(double distance) {
    final double index = distance * TABLE_STEPS_PER_UNIT;
    final int i = (int) index;
    if (i + 1 >= kernel.length) return 0f;
    final float fraction = (float) (index - i);
    return (kernel[i] + (kernel[i + 1] - kernel[i]) * fraction) * kernelScale;
  }

  /// keeps [halfWidth] frames behind the read position for the next outputs' left taps.
  private void discardConsumed() {
    final int drop = (int) position - halfWidth;
    if (drop <= 0) return;
    System.arraycopy(history, drop * channels, history, 0, (historyFrames - drop) * channels);
    historyFrames -= drop;
    position -= drop;
  }

  private void ensureHistory(int frames) {
    final int samples = frames * channels;
    if (history.length >= samples) return;
    final float[] grown = new float[Math.max(samples, history.length * 2)];
    System.arraycopy(history, 0, grown, 0, Math.min(history.length, historyFrames * channels));
    history = grown;
  }

  private void buildKernel(double cutoff) {
    final int size = halfWidth * TABLE_STEPS_PER_UNIT + 2;
    kernel = new float[size];
    final double besselBeta = besselI0(KAISER_BETA);
    for (int i = 0; i < size; i++) {
      final double distance = (double) i / TABLE_STEPS_PER_UNIT;
      if (distance >= halfWidth) break;
      final double x = cutoff * distance;
      final double sinc = x == 0.0 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
      final double ratio = distance / halfWidth;
      final double window = besselI0(KAISER_BETA * Math.sqrt(1.0 - ratio * ratio)) / besselBeta;
      kernel[i] = (float) (sinc * window);
    }
    kernelScale = (float) cutoff;
  }

  private static double besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    final double halfX = x / 2.0;
    for (int k = 1; k < 32; k++) {
      term *= (halfX / k) * (halfX / k);
      sum += term;
      if (term < sum * 1e-12) break;
    }
    return sum;
  }
}
