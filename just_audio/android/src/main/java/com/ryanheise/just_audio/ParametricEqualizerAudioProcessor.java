// by claude
package com.ryanheise.just_audio;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;

/// Float PCM parametric equalizer, preamp, peak limiter and mono downmix following [ParametricEqualizer].
///
/// Filters are linear trapezoidal state variable filters, they stay stable while their coefficients
/// move, so every change ramps smoothly instead of clicking. Bands are matched by id across changes
/// to keep their state, removed ones fade to identity before being dropped.
@UnstableApi
final class ParametricEqualizerAudioProcessor extends BaseAudioProcessor {

  private static final int COEFFICIENTS = 6;
  private static final int MAX_SECTIONS_PER_BAND = 8;
  private static final double RAMP_SECONDS = 0.02;
  private static final double LIMITER_THRESHOLD = 0.999;
  private static final double LIMITER_RELEASE_SECONDS = 0.1;
  private static final ParametricEqualizer.Band[] NO_BANDS = new ParametricEqualizer.Band[0];

  private ParametricEqualizer.Config appliedConfig;
  private int channelCount;
  private double sampleRate;

  private int sectionCount;
  private int[] sectionKeys = new int[0];
  private int[] sectionChannels = new int[0];
  private boolean[] sectionLeaving = new boolean[0];
  private double[] current = new double[0];
  private double[] target = new double[0];
  private double[] step = new double[0];
  private double[] state = new double[0];

  private double preampCurrent = 1.0;
  private double preampTarget = 1.0;
  private double preampStep;
  private int rampFrames;
  private int rampRemaining;

  private boolean limiter;
  private double limiterGain = 1.0;
  private double limiterRelease;

  /// 0 keeps the channels apart, 1 is full mono, ramped so toggling doesn't click.
  private double monoMix;
  private double monoStep;

  private float[] samples = new float[0];

  @Override
  protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    return inputAudioFormat;
  }

  @Override
  protected void onFlush(StreamMetadata streamMetadata) {
    channelCount = inputAudioFormat.channelCount;
    sampleRate = inputAudioFormat.sampleRate;
    rampFrames = Math.max(1, (int) Math.round(RAMP_SECONDS * sampleRate));
    limiterRelease = 1.0 - Math.exp(-1.0 / (LIMITER_RELEASE_SECONDS * sampleRate));
    limiterGain = 1.0;
    monoMix = ParametricEqualizer.isMono() ? 1.0 : 0.0;
    monoStep = 1.0 / rampFrames;
    retarget(ParametricEqualizer.get(), true);
  }

  @Override
  protected void onReset() {
    appliedConfig = null;
    sectionCount = 0;
    rampRemaining = 0;
    preampCurrent = 1.0;
    preampTarget = 1.0;
    limiter = false;
    monoMix = 0.0;
    samples = new float[0];
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    final ParametricEqualizer.Config config = ParametricEqualizer.get();
    if (config != appliedConfig) retarget(config, false);

    final int size = inputBuffer.remaining();
    if (size == 0) return;
    final double monoTarget = ParametricEqualizer.isMono() && channelCount > 1 ? 1.0 : 0.0;
    final ByteBuffer output = replaceOutputBuffer(size);
    if (sectionCount == 0 && rampRemaining == 0 && preampCurrent == 1.0 && !limiter && monoMix == 0.0 && monoTarget == 0.0) {
      output.put(inputBuffer);
      output.flip();
      return;
    }

    final int sampleCount = size / 4;
    if (samples.length < sampleCount) samples = new float[sampleCount];
    inputBuffer.asFloatBuffer().get(samples, 0, sampleCount);
    inputBuffer.position(inputBuffer.limit());

    final int frames = sampleCount / channelCount;
    final int ramp = Math.min(rampRemaining, frames);
    for (int s = 0; s < sectionCount; s++) {
      processSection(s, frames, ramp);
    }
    applyGain(frames, ramp);
    if (ramp > 0) advanceRamp(ramp);
    applyMono(frames, monoTarget);

    output.asFloatBuffer().put(samples, 0, sampleCount);
    output.position(size);
    output.flip();
  }

  private void processSection(int s, int frames, int ramp) {
    final int o = s * COEFFICIENTS;
    final int channel = sectionChannels[s];
    final boolean rampEnds = ramp == rampRemaining;
    for (int ch = 0; ch < channelCount; ch++) {
      if (channel == ParametricEqualizer.CHANNEL_LEFT && ch != 0) continue;
      if (channel == ParametricEqualizer.CHANNEL_RIGHT && ch != 1) continue;

      final int st = (s * channelCount + ch) * 2;
      double ic1 = state[st];
      double ic2 = state[st + 1];
      double a1 = current[o];
      double a2 = current[o + 1];
      double a3 = current[o + 2];
      double m0 = current[o + 3];
      double m1 = current[o + 4];
      double m2 = current[o + 5];
      int i = ch;

      if (ramp > 0) {
        final double da1 = step[o];
        final double da2 = step[o + 1];
        final double da3 = step[o + 2];
        final double dm0 = step[o + 3];
        final double dm1 = step[o + 4];
        final double dm2 = step[o + 5];
        for (int f = 0; f < ramp; f++, i += channelCount) {
          a1 += da1;
          a2 += da2;
          a3 += da3;
          m0 += dm0;
          m1 += dm1;
          m2 += dm2;
          final double v0 = samples[i];
          final double v3 = v0 - ic2;
          final double v1 = a1 * ic1 + a2 * v3;
          final double v2 = ic2 + a2 * ic1 + a3 * v3;
          ic1 = 2.0 * v1 - ic1;
          ic2 = 2.0 * v2 - ic2;
          samples[i] = (float) (m0 * v0 + m1 * v1 + m2 * v2);
        }
        if (rampEnds) {
          a1 = target[o];
          a2 = target[o + 1];
          a3 = target[o + 2];
          m0 = target[o + 3];
          m1 = target[o + 4];
          m2 = target[o + 5];
        }
      }

      for (int f = ramp; f < frames; f++, i += channelCount) {
        final double v0 = samples[i];
        final double v3 = v0 - ic2;
        final double v1 = a1 * ic1 + a2 * v3;
        final double v2 = ic2 + a2 * ic1 + a3 * v3;
        ic1 = 2.0 * v1 - ic1;
        ic2 = 2.0 * v2 - ic2;
        samples[i] = (float) (m0 * v0 + m1 * v1 + m2 * v2);
      }

      state[st] = ic1;
      state[st + 1] = ic2;
    }
  }

  private void applyGain(int frames, int ramp) {
    final boolean rampEnds = ramp == rampRemaining;
    double gain = preampCurrent;
    int i = 0;
    if (!limiter) {
      if (ramp == 0 && gain == 1.0) return;
      for (int f = 0; f < frames; f++) {
        if (f < ramp) gain += preampStep;
        else if (f == ramp && rampEnds) gain = preampTarget;
        for (int ch = 0; ch < channelCount; ch++, i++) {
          samples[i] = (float) (samples[i] * gain);
        }
      }
      return;
    }

    double envelope = limiterGain;
    for (int f = 0; f < frames; f++) {
      if (f < ramp) gain += preampStep;
      else if (f == ramp && rampEnds) gain = preampTarget;
      double peak = 0;
      for (int ch = 0; ch < channelCount; ch++) {
        final double value = Math.abs(samples[i + ch]);
        if (value > peak) peak = value;
      }
      peak *= gain;
      final double wanted = peak > LIMITER_THRESHOLD ? LIMITER_THRESHOLD / peak : 1.0;
      envelope = wanted < envelope ? wanted : Math.min(wanted, envelope + (1.0 - envelope) * limiterRelease);
      final double total = gain * envelope;
      for (int ch = 0; ch < channelCount; ch++, i++) {
        samples[i] = (float) (samples[i] * total);
      }
    }
    limiterGain = envelope;
  }

  /// the average never exceeds the loudest channel, so it can't clip after the limiter.
  private void applyMono(int frames, double monoTarget) {
    if (monoMix == 0.0 && monoTarget == 0.0) return;
    final double channelWeight = 1.0 / channelCount;
    double mix = monoMix;
    int i = 0;
    for (int f = 0; f < frames; f++, i += channelCount) {
      if (mix < monoTarget) {
        mix = Math.min(monoTarget, mix + monoStep);
      } else if (mix > monoTarget) {
        mix = Math.max(monoTarget, mix - monoStep);
      }
      double sum = 0;
      for (int ch = 0; ch < channelCount; ch++) sum += samples[i + ch];
      final double average = sum * channelWeight;
      for (int ch = 0; ch < channelCount; ch++) {
        samples[i + ch] = (float) (samples[i + ch] + (average - samples[i + ch]) * mix);
      }
    }
    monoMix = mix;
  }

  private void advanceRamp(int ramp) {
    rampRemaining -= ramp;
    final int count = sectionCount * COEFFICIENTS;
    if (rampRemaining > 0) {
      for (int j = 0; j < count; j++) {
        current[j] += step[j] * ramp;
      }
      preampCurrent += preampStep * ramp;
      return;
    }
    System.arraycopy(target, 0, current, 0, count);
    preampCurrent = preampTarget;
    dropLeavingSections();
  }

  private void retarget(ParametricEqualizer.Config config, boolean immediate) {
    appliedConfig = config;
    final ParametricEqualizer.Band[] bands = config.enabled ? config.bands : NO_BANDS;

    int newCount = 0;
    for (ParametricEqualizer.Band band : bands) newCount += band.sectionCount();
    final int[] newKeys = new int[newCount];
    final int[] newChannels = new int[newCount];
    final double[] newTarget = new double[newCount * COEFFICIENTS];
    int index = 0;
    for (ParametricEqualizer.Band band : bands) {
      for (int s = 0, n = band.sectionCount(); s < n; s++, index++) {
        newKeys[index] = band.id * MAX_SECTIONS_PER_BAND + s;
        newChannels[index] = band.channel;
        band.design(s, sampleRate, newTarget, index * COEFFICIENTS);
      }
    }

    int leavingCount = 0;
    if (!immediate) {
      for (int old = 0; old < sectionCount; old++) {
        if (indexOf(newKeys, newCount, sectionKeys[old]) < 0) leavingCount++;
      }
    }

    final int total = newCount + leavingCount;
    final int statesPerSection = channelCount * 2;
    final int[] keys = new int[total];
    final int[] channels = new int[total];
    final boolean[] leaving = new boolean[total];
    final double[] nextCurrent = new double[total * COEFFICIENTS];
    final double[] nextTarget = new double[total * COEFFICIENTS];
    final double[] nextState = new double[total * statesPerSection];

    for (int s = 0; s < newCount; s++) {
      final int o = s * COEFFICIENTS;
      keys[s] = newKeys[s];
      channels[s] = newChannels[s];
      System.arraycopy(newTarget, o, nextTarget, o, COEFFICIENTS);
      final int old = immediate ? -1 : indexOf(sectionKeys, sectionCount, newKeys[s]);
      if (old >= 0) {
        System.arraycopy(current, old * COEFFICIENTS, nextCurrent, o, COEFFICIENTS);
        System.arraycopy(state, old * statesPerSection, nextState, s * statesPerSection, statesPerSection);
      } else if (immediate) {
        System.arraycopy(newTarget, o, nextCurrent, o, COEFFICIENTS);
      } else {
        writeIdentity(newTarget, o, nextCurrent, o);
      }
    }

    int s = newCount;
    for (int old = 0; old < sectionCount && leavingCount > 0; old++) {
      if (indexOf(newKeys, newCount, sectionKeys[old]) >= 0) continue;
      final int o = s * COEFFICIENTS;
      keys[s] = sectionKeys[old];
      channels[s] = sectionChannels[old];
      leaving[s] = true;
      System.arraycopy(current, old * COEFFICIENTS, nextCurrent, o, COEFFICIENTS);
      writeIdentity(current, old * COEFFICIENTS, nextTarget, o);
      System.arraycopy(state, old * statesPerSection, nextState, s * statesPerSection, statesPerSection);
      s++;
    }

    sectionCount = total;
    sectionKeys = keys;
    sectionChannels = channels;
    sectionLeaving = leaving;
    current = nextCurrent;
    target = nextTarget;
    state = nextState;
    step = new double[total * COEFFICIENTS];
    preampTarget = config.enabled ? Math.pow(10.0, config.preampDb / 20.0) : 1.0;
    limiter = config.enabled && config.limiter;

    if (immediate) {
      preampCurrent = preampTarget;
      rampRemaining = 0;
      return;
    }
    rampRemaining = rampFrames;
    for (int j = 0; j < step.length; j++) {
      step[j] = (target[j] - current[j]) / rampFrames;
    }
    preampStep = (preampTarget - preampCurrent) / rampFrames;
  }

  private void dropLeavingSections() {
    int kept = 0;
    final int statesPerSection = channelCount * 2;
    for (int s = 0; s < sectionCount; s++) {
      if (sectionLeaving[s]) continue;
      if (kept != s) {
        sectionKeys[kept] = sectionKeys[s];
        sectionChannels[kept] = sectionChannels[s];
        sectionLeaving[kept] = false;
        System.arraycopy(current, s * COEFFICIENTS, current, kept * COEFFICIENTS, COEFFICIENTS);
        System.arraycopy(target, s * COEFFICIENTS, target, kept * COEFFICIENTS, COEFFICIENTS);
        System.arraycopy(state, s * statesPerSection, state, kept * statesPerSection, statesPerSection);
      }
      kept++;
    }
    sectionCount = kept;
  }

  /// identity keeps the section's integrator coefficients so fading in or out only moves the mix.
  private static void writeIdentity(double[] source, int sourceOffset, double[] out, int offset) {
    out[offset] = source[sourceOffset];
    out[offset + 1] = source[sourceOffset + 1];
    out[offset + 2] = source[sourceOffset + 2];
    out[offset + 3] = 1.0;
    out[offset + 4] = 0.0;
    out[offset + 5] = 0.0;
  }

  private static int indexOf(int[] keys, int count, int key) {
    for (int i = 0; i < count; i++) {
      if (keys[i] == key) return i;
    }
    return -1;
  }
}
