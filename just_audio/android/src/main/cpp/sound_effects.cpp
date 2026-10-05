// by claude
//
// Sound effects of the playback chain, float interleaved PCM processed in place at the track's own rate.
// Each effect fades in and out over a short ramp and follows its intensity smoothly, so toggling or dragging never clicks.
// Effects built on a stereo image work on the front pair, the rest on every channel.

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

constexpr int kCrossfeed = 0;
constexpr int kVirtualSurround = 1;
constexpr int kEcho = 2;
constexpr int kChorus = 3;
constexpr int kAutoPan = 4;
constexpr int kCompressor = 5;
constexpr int kInstrumental = 6;
constexpr int kBassEnhancer = 7;
constexpr int kTubeWarmth = 8;
constexpr int kEffectCount = 9;

// -- source shaping first, modulation and time based effects next, the headphone stages last
constexpr int kProcessingOrder[kEffectCount] = {
    kInstrumental, kCompressor, kBassEnhancer, kTubeWarmth, kChorus, kEcho, kAutoPan, kVirtualSurround, kCrossfeed,
};

constexpr int kBlockFrames = 64;
constexpr double kToggleSeconds = 0.03;
constexpr double kIntensitySeconds = 0.06;
constexpr double kPi = 3.14159265358979323846;
constexpr double kLimiterThreshold = 0.98;
constexpr double kLimiterReleaseSeconds = 0.08;

struct Biquad {
  double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
  double z1 = 0.0, z2 = 0.0;

  void lowPass(double frequency, double q, double rate) { design(frequency, q, rate, false); }
  void highPass(double frequency, double q, double rate) { design(frequency, q, rate, true); }

  double run(double x) {
    const double y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
  }

  void clear() { z1 = z2 = 0.0; }

 private:
  void design(double frequency, double q, double rate, bool isHighPass) {
    const double clamped = std::min(frequency, rate * 0.45);
    const double w0 = 2.0 * kPi * clamped / rate;
    const double cosW = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    const double edge = isHighPass ? (1.0 + cosW) * 0.5 : (1.0 - cosW) * 0.5;
    b0 = edge / a0;
    b1 = (isHighPass ? -2.0 * edge : 2.0 * edge) / a0;
    b2 = edge / a0;
    a1 = -2.0 * cosW / a0;
    a2 = (1.0 - alpha) / a0;
  }
};

struct OnePoleLowPass {
  double coefficient = 1.0;
  double z = 0.0;

  void set(double frequency, double rate) { coefficient = 1.0 - std::exp(-2.0 * kPi * std::min(frequency, rate * 0.45) / rate); }
  double run(double x) { return z += (x - z) * coefficient; }
  void clear() { z = 0.0; }
};

class DelayLine {
 public:
  void prepare(int maxDelay) {
    size_t size = 1;
    while (size < static_cast<size_t>(maxDelay) + 2) size <<= 1;
    buffer_.assign(size, 0.0f);
    mask_ = size - 1;
    write_ = 0;
  }

  void clear() { std::fill(buffer_.begin(), buffer_.end(), 0.0f); }

  void push(float value) {
    buffer_[write_] = value;
    write_ = (write_ + 1) & mask_;
  }

  /// [delay] samples behind the last pushed one, at least 1.
  float read(double delay) const {
    const double position = static_cast<double>(write_) - delay;
    const double floorPosition = std::floor(position);
    const float fraction = static_cast<float>(position - floorPosition);
    const size_t index = static_cast<size_t>(static_cast<int64_t>(floorPosition)) & mask_;
    const float a = buffer_[index];
    const float b = buffer_[(index + 1) & mask_];
    return a + (b - a) * fraction;
  }

 private:
  std::vector<float> buffer_;
  size_t mask_ = 0;
  size_t write_ = 0;
};

/// gain ramp across one block, from the effect's previous mix to its next one.
struct Ramp {
  float start;
  float step;
};

/// removes what sits in the middle of the stereo image between 150 Hz and 7 kHz, the voice, keeping bass and cymbals.
class Instrumental {
 public:
  void prepare(double rate) {
    highPass_.highPass(150.0, 0.7071, rate);
    lowPass_.lowPass(7000.0, 0.7071, rate);
  }

  void clear() {
    highPass_.clear();
    lowPass_.clear();
  }

  void process(float* x, int frames, int channels, Ramp ramp, float intensity) {
    if (channels < 2) return;
    float mix = ramp.start;
    for (int f = 0; f < frames; f++, x += channels) {
      mix += ramp.step;
      const double mid = (x[0] + x[1]) * 0.5;
      const double side = (x[0] - x[1]) * 0.5;
      const double voice = lowPass_.run(highPass_.run(mid));
      const double kept = mid - voice * intensity * mix;
      x[0] = static_cast<float>(kept + side);
      x[1] = static_cast<float>(kept - side);
    }
  }

 private:
  Biquad highPass_;
  Biquad lowPass_;
};

/// soft knee feed forward compressor, channels linked, made up so peaks around -8 dBFS keep their level.
class Compressor {
 public:
  void prepare(double rate) {
    attack_ = std::exp(-1.0 / (0.003 * rate));
    release_ = std::exp(-1.0 / (0.2 * rate));
  }

  void clear() { envelopeDb_ = 0.0; }

  void process(float* x, int frames, int channels, Ramp ramp, float intensity) {
    constexpr double kKneeDb = 6.0;
    constexpr double kDbPerLog = 8.685889638065035;
    constexpr double kLogPerDb = 0.11512925464970229;
    constexpr double kReferenceDb = -8.0;
    const double thresholdDb = -30.0 * intensity;
    const double ratio = 1.0 + 3.0 * intensity;
    const double slope = 1.0 - 1.0 / ratio;
    const double makeupDb = std::max(0.0, kReferenceDb - thresholdDb) * slope;
    float mix = ramp.start;
    for (int f = 0; f < frames; f++, x += channels) {
      mix += ramp.step;
      float peak = 0.0f;
      for (int c = 0; c < channels; c++) peak = std::max(peak, std::fabs(x[c]));
      const double levelDb = std::log(std::max(static_cast<double>(peak), 1e-6)) * kDbPerLog;
      const double over = levelDb - thresholdDb;
      double reductionDb;
      if (over <= -kKneeDb * 0.5) {
        reductionDb = 0.0;
      } else if (over >= kKneeDb * 0.5) {
        reductionDb = -over * slope;
      } else {
        const double kneeOver = over + kKneeDb * 0.5;
        reductionDb = -slope * kneeOver * kneeOver / (2.0 * kKneeDb);
      }
      const double coefficient = reductionDb < envelopeDb_ ? attack_ : release_;
      envelopeDb_ = coefficient * envelopeDb_ + (1.0 - coefficient) * reductionDb;
      const double gain = std::exp((envelopeDb_ + makeupDb) * kLogPerDb);
      const float applied = static_cast<float>(1.0 + mix * (gain - 1.0));
      for (int c = 0; c < channels; c++) x[c] *= applied;
    }
  }

 private:
  double attack_ = 0.0;
  double release_ = 0.0;
  double envelopeDb_ = 0.0;
};

/// adds the harmonics of the lowest notes, small drivers that can't play them still give the ear their pitch.
class BassEnhancer {
 public:
  void prepare(double rate) {
    lowA_.lowPass(120.0, 0.7071, rate);
    lowB_.lowPass(120.0, 0.7071, rate);
    harmonicsHighPass_.highPass(90.0, 0.7071, rate);
    harmonicsLowPass_.lowPass(600.0, 0.7071, rate);
  }

  void clear() {
    lowA_.clear();
    lowB_.clear();
    harmonicsHighPass_.clear();
    harmonicsLowPass_.clear();
  }

  void process(float* x, int frames, int channels, Ramp ramp, float intensity) {
    const bool isStereo = channels >= 2;
    float mix = ramp.start;
    for (int f = 0; f < frames; f++, x += channels) {
      mix += ramp.step;
      const double mono = isStereo ? (x[0] + x[1]) * 0.5 : x[0];
      const double low = lowB_.run(lowA_.run(mono));
      const double harmonics = harmonicsLowPass_.run(harmonicsHighPass_.run(std::fabs(low)));
      const float added = static_cast<float>(mix * intensity * (1.6 * harmonics + 0.35 * low));
      x[0] += added;
      if (isStereo) x[1] += added;
    }
  }

 private:
  Biquad lowA_;
  Biquad lowB_;
  Biquad harmonicsHighPass_;
  Biquad harmonicsLowPass_;
};

/// a gentle waveshaper, mostly even harmonics like a triode with a few odd ones, and a softer top end.
class TubeWarmth {
 public:
  void prepare(double rate, int channels) {
    dcCoefficient_ = 1.0 - 2.0 * kPi * 5.0 / rate;
    dcInput_.assign(channels, 0.0);
    dcOutput_.assign(channels, 0.0);
    tone_.assign(channels, OnePoleLowPass());
    for (OnePoleLowPass& t : tone_) t.set(7000.0, rate);
  }

  void clear() {
    std::fill(dcInput_.begin(), dcInput_.end(), 0.0);
    std::fill(dcOutput_.begin(), dcOutput_.end(), 0.0);
    for (OnePoleLowPass& t : tone_) t.clear();
  }

  void process(float* x, int frames, int channels, Ramp ramp, float intensity) {
    // -- the curve stays monotonic up to the clamp
    constexpr double kShapeLimit = 1.4;
    const double even = 0.35 * intensity;
    const double odd = 0.15 * intensity;
    const double toneCut = 0.5 * intensity;
    float mix = ramp.start;
    for (int f = 0; f < frames; f++, x += channels) {
      mix += ramp.step;
      for (int c = 0; c < channels; c++) {
        const double input = x[c];
        const double clamped = std::clamp(input, -kShapeLimit, kShapeLimit);
        const double squared = clamped * clamped;
        const double shaped = input + even * squared - odd * squared * clamped;
        const double withoutDc = shaped - dcInput_[c] + dcCoefficient_ * dcOutput_[c];
        dcInput_[c] = shaped;
        dcOutput_[c] = withoutDc;
        const double warm = withoutDc + toneCut * (tone_[c].run(withoutDc) - withoutDc);
        x[c] = static_cast<float>(input + mix * (warm - input));
      }
    }
  }

 private:
  double dcCoefficient_ = 0.0;
  std::vector<double> dcInput_;
  std::vector<double> dcOutput_;
  std::vector<OnePoleLowPass> tone_;
};

/// one modulated voice per side, a quarter cycle apart so the two sides move independently.
class Chorus {
 public:
  void prepare(double rate) {
    rate_ = rate;
    for (DelayLine& line : lines_) line.prepare(static_cast<int>(0.04 * rate));
    phaseStep_ = 2.0 * kPi * 0.5 / rate;
  }

  void clear() {
    for (DelayLine& line : lines_) line.clear();
    phase_ = 0.0;
  }

  void process(float* x, int frames, int channels, Ramp ramp, float intensity) {
    const int voices = std::min(channels, 2);
    const double baseDelay = 0.012 * rate_;
    const double depth = (0.0015 + 0.004 * intensity) * rate_;
    const double wet = 0.5 * intensity;
    const double normalize = 1.0 / (1.0 + 0.5 * wet);
    float mix = ramp.start;
    for (int f = 0; f < frames; f++, x += channels) {
      mix += ramp.step;
      for (int c = 0; c < voices; c++) {
        const double lfo = std::sin(phase_ + c * kPi * 0.5);
        const double delay = baseDelay + depth * (0.5 + 0.5 * lfo);
        lines_[c].push(x[c]);
        const double voiced = (x[c] + wet * lines_[c].read(delay)) * normalize;
        x[c] = static_cast<float>(x[c] + mix * (voiced - x[c]));
      }
      phase_ += phaseStep_;
      if (phase_ > 2.0 * kPi) phase_ -= 2.0 * kPi;
    }
  }

 private:
  double rate_ = 48000.0;
  DelayLine lines_[2];
  double phase_ = 0.0;
  double phaseStep_ = 0.0;
};

/// ping-pong echo, each side's repeats feed the other one, softened a bit every pass.
class Echo {
 public:
  void prepare(double rate) {
    for (DelayLine& line : lines_) line.prepare(static_cast<int>(0.5 * rate));
    for (OnePoleLowPass& damp : damping_) damp.set(3500.0, rate);
    delays_[0] = 0.33 * rate;
    delays_[1] = 0.36 * rate;
  }

  void clear() {
    for (DelayLine& line : lines_) line.clear();
    for (OnePoleLowPass& damp : damping_) damp.clear();
  }

  void process(float* x, int frames, int channels, Ramp ramp, float intensity) {
    const double feedback = 0.2 + 0.4 * intensity;
    const double wet = 0.55 * intensity;
    float mix = ramp.start;
    for (int f = 0; f < frames; f++, x += channels) {
      mix += ramp.step;
      if (channels < 2) {
        const double delayed = lines_[0].read(delays_[0]);
        lines_[0].push(static_cast<float>(x[0] + feedback * damping_[0].run(delayed)));
        x[0] = static_cast<float>(x[0] + mix * wet * delayed);
        continue;
      }
      const double left = lines_[0].read(delays_[0]);
      const double right = lines_[1].read(delays_[1]);
      lines_[0].push(static_cast<float>(x[0] + feedback * damping_[1].run(right)));
      lines_[1].push(static_cast<float>(x[1] + feedback * damping_[0].run(left)));
      x[0] = static_cast<float>(x[0] + mix * wet * left);
      x[1] = static_cast<float>(x[1] + mix * wet * right);
    }
  }

 private:
  DelayLine lines_[2];
  OnePoleLowPass damping_[2];
  double delays_[2] = {0.0, 0.0};
};

/// moves the middle of the stereo image around the head in an 8 second circle, the sides stay put a little.
class AutoPan {
 public:
  void prepare(double rate) { phaseStep_ = 2.0 * kPi * 0.125 / rate; }

  void clear() { phase_ = 0.0; }

  void process(float* x, int frames, int channels, Ramp ramp, float intensity) {
    if (channels < 2) return;
    const double depth = intensity;
    const double sideKept = 1.0 - 0.6 * depth;
    float mix = ramp.start;
    for (int f = 0; f < frames; f++, x += channels) {
      mix += ramp.step;
      const double position = depth * std::sin(phase_);
      const double angle = (position + 1.0) * kPi * 0.25;
      const double leftGain = std::cos(angle) * 1.4142135623730951;
      const double rightGain = std::sin(angle) * 1.4142135623730951;
      const double mid = (x[0] + x[1]) * 0.5;
      const double side = (x[0] - x[1]) * 0.5 * sideKept;
      const double left = mid * leftGain + side;
      const double right = mid * rightGain - side;
      x[0] = static_cast<float>(x[0] + mix * (left - x[0]));
      x[1] = static_cast<float>(x[1] + mix * (right - x[1]));
      phase_ += phaseStep_;
      if (phase_ > 2.0 * kPi) phase_ -= 2.0 * kPi;
    }
  }

 private:
  double phase_ = 0.0;
  double phaseStep_ = 0.0;
};

/// speakers rendered around a spherical head (Brown & Duda 1998): two in front at ±30° playing the track,
/// two behind at ±110° playing its decorrelated sides, plus a few early reflections so it sits outside the head.
class VirtualSurround {
 public:
  void prepare(double rate) {
    constexpr double kHeadRadius = 0.0875;
    constexpr double kSpeedOfSound = 343.0;
    constexpr double kSourceAngles[kSources] = {-30.0, 30.0, -110.0, 110.0};
    constexpr double kEarAngles[2] = {-90.0, 90.0};
    const double headDelay = kHeadRadius / kSpeedOfSound;
    const double shelfTime = kHeadRadius / (2.0 * kSpeedOfSound);
    const double bilinear = 2.0 * rate;

    int maxDelay = 0;
    for (int s = 0; s < kSources; s++) {
      for (int e = 0; e < 2; e++) {
        double incidence = std::fabs(kSourceAngles[s] - kEarAngles[e]);
        if (incidence > 180.0) incidence = 360.0 - incidence;
        const double radians = incidence * kPi / 180.0;
        const double interaural = radians < kPi * 0.5 ? -headDelay * std::cos(radians) : headDelay * (radians - kPi * 0.5);
        Path& path = paths_[s][e];
        path.delay = (headDelay + interaural) * rate + 1.0;
        maxDelay = std::max(maxDelay, static_cast<int>(path.delay) + 2);

        const double shadow = 1.05 + 0.95 * std::cos(incidence / 150.0 * kPi);
        const double denominator = 1.0 + shelfTime * bilinear;
        path.b0 = (1.0 + shadow * shelfTime * bilinear) / denominator;
        path.b1 = (1.0 - shadow * shelfTime * bilinear) / denominator;
        path.a1 = (1.0 - shelfTime * bilinear) / denominator;
      }
    }
    for (DelayLine& line : sources_) line.prepare(maxDelay);
    rearDelay_ = 0.012 * rate;
    rearLine_.prepare(static_cast<int>(rearDelay_) + 2);

    constexpr double kReflectionSeconds[kReflections] = {0.007, 0.011, 0.017, 0.023};
    for (int r = 0; r < kReflections; r++) reflectionDelays_[r] = kReflectionSeconds[r] * rate;
    reflectionLine_.prepare(static_cast<int>(reflectionDelays_[kReflections - 1]) + 2);
    for (OnePoleLowPass& tone : reflectionTone_) tone.set(5000.0, rate);
  }

  void clear() {
    for (DelayLine& line : sources_) line.clear();
    rearLine_.clear();
    reflectionLine_.clear();
    for (OnePoleLowPass& tone : reflectionTone_) tone.clear();
    for (auto& ears : paths_) {
      for (Path& path : ears) path.clear();
    }
  }

  void process(float* x, int frames, int channels, Ramp ramp, float intensity) {
    if (channels < 2) return;
    constexpr double kReflectionGains[kReflections] = {0.3, 0.25, 0.18, 0.12};
    constexpr double kNormalize = 0.6;
    const double rearGain = 0.4 + 0.35 * intensity;
    const double reflectionGain = 0.25 + 0.15 * intensity;
    const double blend = intensity;
    float mix = ramp.start;
    for (int f = 0; f < frames; f++, x += channels) {
      mix += ramp.step;
      const double left = x[0];
      const double right = x[1];
      const double side = (left - right) * 0.5;
      rearLine_.push(static_cast<float>(side));
      const double rear = rearLine_.read(rearDelay_) * rearGain;
      sources_[0].push(static_cast<float>(left));
      sources_[1].push(static_cast<float>(right));
      sources_[2].push(static_cast<float>(rear));
      sources_[3].push(static_cast<float>(-rear));

      double ears[2] = {0.0, 0.0};
      for (int s = 0; s < kSources; s++) {
        for (int e = 0; e < 2; e++) ears[e] += paths_[s][e].run(sources_[s].read(paths_[s][e].delay));
      }

      reflectionLine_.push(static_cast<float>((left + right) * 0.5));
      double reflections[2] = {0.0, 0.0};
      for (int r = 0; r < kReflections; r++) reflections[r & 1] += reflectionLine_.read(reflectionDelays_[r]) * kReflectionGains[r];
      for (int e = 0; e < 2; e++) ears[e] = ears[e] * kNormalize + reflectionTone_[e].run(reflections[e]) * reflectionGain;

      const double amount = mix * blend;
      x[0] = static_cast<float>(left + amount * (ears[0] - left));
      x[1] = static_cast<float>(right + amount * (ears[1] - right));
    }
  }

 private:
  static constexpr int kSources = 4;
  static constexpr int kReflections = 4;

  /// interaural delay and the head's first order high shelf, from one virtual speaker to one ear.
  struct Path {
    double delay = 1.0;
    double b0 = 1.0, b1 = 0.0, a1 = 0.0;
    double x1 = 0.0, y1 = 0.0;

    double run(double x) {
      const double y = b0 * x + b1 * x1 - a1 * y1;
      x1 = x;
      y1 = y;
      return y;
    }

    void clear() { x1 = y1 = 0.0; }
  };

  DelayLine sources_[kSources];
  Path paths_[kSources][2];
  DelayLine rearLine_;
  double rearDelay_ = 0.0;
  DelayLine reflectionLine_;
  double reflectionDelays_[kReflections] = {};
  OnePoleLowPass reflectionTone_[2];
};

/// lets each ear hear the other side's lows slightly late like speakers would, the middle of the image stays as is.
class Crossfeed {
 public:
  void prepare(double rate) {
    for (OnePoleLowPass& lowPass : lowPasses_) lowPass.set(700.0, rate);
  }

  void clear() {
    for (OnePoleLowPass& lowPass : lowPasses_) lowPass.clear();
  }

  void process(float* x, int frames, int channels, Ramp ramp, float intensity) {
    if (channels < 2) return;
    const double feed = 0.5 * intensity;
    float mix = ramp.start;
    for (int f = 0; f < frames; f++, x += channels) {
      mix += ramp.step;
      const double leftLows = lowPasses_[0].run(x[0]);
      const double rightLows = lowPasses_[1].run(x[1]);
      const float exchanged = static_cast<float>(feed * mix * (rightLows - leftLows));
      x[0] += exchanged;
      x[1] -= exchanged;
    }
  }

 private:
  OnePoleLowPass lowPasses_[2];
};

class SoundEffects {
 public:
  void configure(int sampleRate, int channels) {
    rate_ = sampleRate;
    channels_ = std::max(channels, 1);
    toggleStep_ = static_cast<float>(1.0 / (kToggleSeconds * rate_));
    intensityCoefficient_ = static_cast<float>(1.0 - std::exp(-kBlockFrames / (kIntensitySeconds * rate_)));
    limiterRelease_ = 1.0 - std::exp(-1.0 / (kLimiterReleaseSeconds * rate_));
    instrumental_.prepare(rate_);
    compressor_.prepare(rate_);
    bassEnhancer_.prepare(rate_);
    tubeWarmth_.prepare(rate_, channels_);
    chorus_.prepare(rate_);
    echo_.prepare(rate_);
    autoPan_.prepare(rate_);
    virtualSurround_.prepare(rate_);
    crossfeed_.prepare(rate_);
    reset();
  }

  /// a disabled effect keeps its last intensity while it fades out.
  void setEffects(uint32_t enabledMask, const float* intensities) {
    enabledMask_ = enabledMask;
    for (int e = 0; e < kEffectCount; e++) {
      if ((enabledMask >> e) & 1u) targetIntensities_[e] = std::clamp(intensities[e], 0.0f, 1.0f);
    }
  }

  /// in place, returns whether any effect is still audible, an idle chain needs no further calls until effects change.
  bool process(float* data, int frames) {
    bool isBusy = false;
    for (int offset = 0; offset < frames; offset += kBlockFrames) {
      const int blockFrames = std::min(kBlockFrames, frames - offset);
      isBusy = processBlock(data + static_cast<size_t>(offset) * channels_, blockFrames);
    }
    return isBusy;
  }

  /// drops every tail and filter state, enabled effects resume at full mix since the audio restarts anyway.
  void reset() {
    for (int e = 0; e < kEffectCount; e++) {
      mixes_[e] = (enabledMask_ >> e) & 1u ? 1.0f : 0.0f;
      intensities_[e] = targetIntensities_[e];
      clearEffect(e);
    }
    limiterGain_ = 1.0;
  }

 private:
  double rate_ = 48000.0;
  int channels_ = 2;
  uint32_t enabledMask_ = 0;
  float targetIntensities_[kEffectCount] = {};
  float intensities_[kEffectCount] = {};
  float mixes_[kEffectCount] = {};
  float toggleStep_ = 0.0f;
  float intensityCoefficient_ = 0.0f;
  double limiterGain_ = 1.0;
  double limiterRelease_ = 0.0;

  Instrumental instrumental_;
  Compressor compressor_;
  BassEnhancer bassEnhancer_;
  TubeWarmth tubeWarmth_;
  Chorus chorus_;
  Echo echo_;
  AutoPan autoPan_;
  VirtualSurround virtualSurround_;
  Crossfeed crossfeed_;

  bool processBlock(float* x, int frames) {
    bool isAnyAudible = false;
    bool isBusy = false;
    for (int e : kProcessingOrder) {
      const float target = (enabledMask_ >> e) & 1u ? 1.0f : 0.0f;
      const float start = mixes_[e];
      if (start == 0.0f && target == 0.0f) continue;
      if (start == 0.0f) {
        // -- a fresh start, stale tails and filter states of the last run are dropped
        clearEffect(e);
        intensities_[e] = targetIntensities_[e];
      } else {
        intensities_[e] += (targetIntensities_[e] - intensities_[e]) * intensityCoefficient_;
      }
      const float change = toggleStep_ * static_cast<float>(frames);
      const float end = target > start ? std::min(target, start + change) : std::max(target, start - change);
      mixes_[e] = end;
      runEffect(e, x, frames, Ramp{start, (end - start) / frames}, intensities_[e]);
      isAnyAudible = true;
      if (end > 0.0f) isBusy = true;
    }
    if (isAnyAudible) limit(x, frames);
    return isBusy;
  }

  void runEffect(int effect, float* x, int frames, Ramp ramp, float intensity) {
    switch (effect) {
      case kInstrumental:
        instrumental_.process(x, frames, channels_, ramp, intensity);
        break;
      case kCompressor:
        compressor_.process(x, frames, channels_, ramp, intensity);
        break;
      case kBassEnhancer:
        bassEnhancer_.process(x, frames, channels_, ramp, intensity);
        break;
      case kTubeWarmth:
        tubeWarmth_.process(x, frames, channels_, ramp, intensity);
        break;
      case kChorus:
        chorus_.process(x, frames, channels_, ramp, intensity);
        break;
      case kEcho:
        echo_.process(x, frames, channels_, ramp, intensity);
        break;
      case kAutoPan:
        autoPan_.process(x, frames, channels_, ramp, intensity);
        break;
      case kVirtualSurround:
        virtualSurround_.process(x, frames, channels_, ramp, intensity);
        break;
      case kCrossfeed:
        crossfeed_.process(x, frames, channels_, ramp, intensity);
        break;
      default:
        break;
    }
  }

  void clearEffect(int effect) {
    switch (effect) {
      case kInstrumental:
        instrumental_.clear();
        break;
      case kCompressor:
        compressor_.clear();
        break;
      case kBassEnhancer:
        bassEnhancer_.clear();
        break;
      case kTubeWarmth:
        tubeWarmth_.clear();
        break;
      case kChorus:
        chorus_.clear();
        break;
      case kEcho:
        echo_.clear();
        break;
      case kAutoPan:
        autoPan_.clear();
        break;
      case kVirtualSurround:
        virtualSurround_.clear();
        break;
      case kCrossfeed:
        crossfeed_.clear();
        break;
      default:
        break;
    }
  }

  /// effects that add energy (bass, echo, surround) can push peaks past full scale, caught here instead of clipping.
  void limit(float* x, int frames) {
    double gain = limiterGain_;
    for (int f = 0; f < frames; f++, x += channels_) {
      float peak = 0.0f;
      for (int c = 0; c < channels_; c++) peak = std::max(peak, std::fabs(x[c]));
      const double wanted = peak * gain > kLimiterThreshold ? kLimiterThreshold / peak : 1.0;
      gain = wanted < gain ? wanted : std::min(wanted, gain + (1.0 - gain) * limiterRelease_);
      if (gain == 1.0) continue;
      const float applied = static_cast<float>(gain);
      for (int c = 0; c < channels_; c++) x[c] *= applied;
    }
    limiterGain_ = gain;
  }
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_ryanheise_just_1audio_SoundEffectsAudioProcessor_nativeCreate(JNIEnv *, jclass) {
  return reinterpret_cast<jlong>(new SoundEffects());
}

JNIEXPORT void JNICALL Java_com_ryanheise_just_1audio_SoundEffectsAudioProcessor_nativeConfigure(JNIEnv *, jclass, jlong handle, jint sampleRate,
                                                                                               jint channels) {
  reinterpret_cast<SoundEffects *>(handle)->configure(sampleRate, channels);
}

JNIEXPORT void JNICALL Java_com_ryanheise_just_1audio_SoundEffectsAudioProcessor_nativeSetEffects(JNIEnv *env, jclass, jlong handle, jint enabledMask,
                                                                                                jfloatArray intensities) {
  float values[kEffectCount] = {};
  const jsize count = std::min(env->GetArrayLength(intensities), static_cast<jsize>(kEffectCount));
  env->GetFloatArrayRegion(intensities, 0, count, values);
  reinterpret_cast<SoundEffects *>(handle)->setEffects(static_cast<uint32_t>(enabledMask), values);
}

JNIEXPORT jboolean JNICALL Java_com_ryanheise_just_1audio_SoundEffectsAudioProcessor_nativeProcess(JNIEnv *env, jclass, jlong handle, jobject buffer,
                                                                                                 jint frames) {
  auto *data = static_cast<float *>(env->GetDirectBufferAddress(buffer));
  if (data == nullptr) return JNI_FALSE;
  return reinterpret_cast<SoundEffects *>(handle)->process(data, frames) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_ryanheise_just_1audio_SoundEffectsAudioProcessor_nativeReset(JNIEnv *, jclass, jlong handle) {
  reinterpret_cast<SoundEffects *>(handle)->reset();
}

JNIEXPORT void JNICALL Java_com_ryanheise_just_1audio_SoundEffectsAudioProcessor_nativeRelease(JNIEnv *, jclass, jlong handle) {
  delete reinterpret_cast<SoundEffects *>(handle);
}

}  // extern "C"
