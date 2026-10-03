// by claude, streaming model based on decent-player's usb-audio-output (MIT, see LICENSE-decent-player)
//
// PCM written by the player lands in a lock-free ring, a dedicated thread moves it into isochronous URBs sized by
// the DAC's own clock (async feedback endpoint). Integer input in the DAC's own format is copied untouched, float input
// (tracks the DAC can't take losslessly, already resampled) is quantized to the DAC's resolution with TPDF dither.
// Audio leaves the ring only once its URB is reaped, a pause discards the queued URBs and rewinds to what was really played.

#include <jni.h>

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/resource.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>

#define LOG_TAG "UsbAudioStream"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// -- 384 ms queued at the controller, the streaming thread isn't realtime and app launches can hold it off for a while (#1264)
constexpr int kUrbCount = 48;
constexpr int kUrbDurationMs = 8;
constexpr int kMaxPacketsPerUrb = 64;
constexpr int kUnderrunUrbs = 2;
constexpr int kRingDurationMs = 250;
constexpr int kUrgentAudioPriority = -19;
constexpr int kUnknownFeedbackShift = INT_MIN;
constexpr int64_t kStallReportIntervalUs = 1000000;

using Clock = std::chrono::steady_clock;

struct Conversion {
  bool inputFloat;
  int inputChannels;
  int inputBytesPerSample;
  int outputChannels;
  int subslotBytes;
  int resolution;

  bool isPassthrough() const { return !inputFloat && inputChannels == outputChannels && inputBytesPerSample == subslotBytes; }
  int inputFrameBytes() const { return inputChannels * inputBytesPerSample; }
  int outputFrameBytes() const { return outputChannels * subslotBytes; }
};

struct Slot {
  usbdevfs_urb *urb = nullptr;
  uint8_t *buffer = nullptr;
  size_t mappedBytes = 0;
  int64_t audioFrames = 0;
  bool inFlight = false;
};

/// what starved the stream since the last report, logged so `adb logcat -s UsbAudioStream` shows where a stutter came from.
struct StallStats {
  int64_t starvedFrames = 0;
  int64_t clampedFrames = 0;
  int missedPackets = 0;
  int64_t longestReapGapUs = 0;
  int64_t slowestSubmitUs = 0;

  bool hasStalls(int64_t urbDurationUs) const {
    return starvedFrames > 0 || clampedFrames > 0 || missedPackets > 0 || longestReapGapUs > urbDurationUs * 3;
  }
};

class UsbAudioStream {
 public:
  UsbAudioStream(int fd, int endpoint, int feedbackEndpoint, int feedbackBytes, int packetsPerSecond, int sampleRate, Conversion conversion,
                 int maxPacketBytes)
      : fd_(fd),
        endpoint_(endpoint),
        feedbackEndpoint_(feedbackEndpoint),
        feedbackBytes_(feedbackBytes),
        packetsPerSecond_(packetsPerSecond),
        sampleRate_(sampleRate),
        conversion_(conversion),
        isPassthrough_(conversion.isPassthrough()),
        inputFrameBytes_(conversion.inputFrameBytes()),
        bytesPerFrame_(conversion.outputFrameBytes()),
        maxPacketBytes_(maxPacketBytes),
        nominalFramesPerPacket_(static_cast<double>(sampleRate) / packetsPerSecond),
        framesPerPacket_(nominalFramesPerPacket_) {
    const int packets = packetsPerSecond * kUrbDurationMs / 1000;
    packetsPerUrb_ = packets < 1 ? 1 : (packets > kMaxPacketsPerUrb ? kMaxPacketsPerUrb : packets);
    urbDurationUs_ = static_cast<int64_t>(packetsPerUrb_) * 1000000 / packetsPerSecond;
  }

  ~UsbAudioStream() {
    for (Slot &slot : slots_) {
      free(slot.urb);
      if (slot.mappedBytes > 0) {
        munmap(slot.buffer, slot.mappedBytes);
      } else {
        free(slot.buffer);
      }
    }
    free(feedbackUrb_);
    free(ring_);
  }

  bool init() {
    // -- packets are clamped to what the endpoint takes, like snd-usb-audio does, so a tightly sized endpoint still streams
    maxFramesPerPacket_ = maxPacketBytes_ / bytesPerFrame_;
    if (static_cast<int>(std::ceil(nominalFramesPerPacket_)) > maxFramesPerPacket_) return false;
    const size_t urbSize = sizeof(usbdevfs_urb) + packetsPerUrb_ * sizeof(usbdevfs_iso_packet_desc);
    const size_t bufferSize = static_cast<size_t>(packetsPerUrb_) * maxFramesPerPacket_ * bytesPerFrame_;
    for (Slot &slot : slots_) {
      slot.urb = static_cast<usbdevfs_urb *>(calloc(1, urbSize));
      if (slot.urb == nullptr || !allocateBuffer(slot, bufferSize)) return false;
    }
    if (feedbackEndpoint_ > 0) {
      feedbackUrb_ = static_cast<usbdevfs_urb *>(calloc(1, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc)));
      if (feedbackUrb_ == nullptr) return false;
    }
    // -- queued urbs keep their audio in the ring until reaped, the player still gets the whole ring duration on top
    const int64_t maxQueuedFrames = static_cast<int64_t>(kUrbCount) * packetsPerUrb_ * maxFramesPerPacket_;
    ringCapacityFrames_ = static_cast<int64_t>(sampleRate_) * kRingDurationMs / 1000 + maxQueuedFrames;
    ring_ = static_cast<uint8_t *>(malloc(static_cast<size_t>(ringCapacityFrames_ * inputFrameBytes_)));
    if (ring_ == nullptr) return false;
    LOGI("stream: %d Hz, %d ch, %d-bit in %d-byte slots, %d packets/s, %d packets/urb, max %d frames/packet, feedback 0x%x, %s buffers",
         sampleRate_, conversion_.outputChannels, conversion_.resolution, conversion_.subslotBytes, packetsPerSecond_, packetsPerUrb_,
         maxFramesPerPacket_, feedbackEndpoint_, slots_[0].mappedBytes > 0 ? "mapped" : "copied");
    thread_ = std::thread(&UsbAudioStream::run, this);
    return true;
  }

  /// returns the input bytes consumed, whole frames only.
  int write(const uint8_t *data, int bytes) {
    const uint64_t writeFrame = writeFrame_.load(std::memory_order_relaxed);
    const uint64_t committedFrame = committedFrame_.load(std::memory_order_acquire);
    const int64_t freeFrames = ringCapacityFrames_ - static_cast<int64_t>(writeFrame - committedFrame);
    int64_t frames = bytes / inputFrameBytes_;
    if (frames > freeFrames) frames = freeFrames;
    if (frames <= 0) return 0;
    const int64_t ringIndex = static_cast<int64_t>(writeFrame % static_cast<uint64_t>(ringCapacityFrames_));
    const int64_t firstFrames = std::min(frames, ringCapacityFrames_ - ringIndex);
    memcpy(ring_ + ringIndex * inputFrameBytes_, data, static_cast<size_t>(firstFrames * inputFrameBytes_));
    memcpy(ring_, data + firstFrames * inputFrameBytes_, static_cast<size_t>((frames - firstFrames) * inputFrameBytes_));
    writeFrame_.store(writeFrame + frames, std::memory_order_release);
    endOfStream_.store(false);
    wake();
    return static_cast<int>(frames * inputFrameBytes_);
  }

  void setPlaying(bool playing) {
    playing_.store(playing);
    wake();
  }

  void endOfStream() {
    endOfStream_.store(true);
    wake();
  }

  void setGain(float gain) { gain_.store(gain, std::memory_order_relaxed); }

  int64_t playedFrames() const { return playedFrames_.load(std::memory_order_acquire); }

  int errorCode() const { return errorCode_.load(); }

  void release() {
    released_.store(true);
    wake();
    for (Slot &slot : slots_) {
      if (slot.urb != nullptr) ioctl(fd_, USBDEVFS_DISCARDURB, slot.urb);
    }
    if (feedbackUrb_ != nullptr) ioctl(fd_, USBDEVFS_DISCARDURB, feedbackUrb_);
    if (thread_.joinable()) thread_.join();
  }

 private:
  const int fd_;
  const int endpoint_;
  const int feedbackEndpoint_;
  const int feedbackBytes_;
  const int packetsPerSecond_;
  const int sampleRate_;
  const Conversion conversion_;
  const bool isPassthrough_;
  const int inputFrameBytes_;
  const int bytesPerFrame_;
  const int maxPacketBytes_;
  const double nominalFramesPerPacket_;
  int maxFramesPerPacket_ = 0;
  double framesPerPacket_;
  double frameAccumulator_ = 0.0;
  int packetsPerUrb_ = 1;
  int64_t urbDurationUs_ = 0;
  int feedbackShift_ = kUnknownFeedbackShift;

  Slot slots_[kUrbCount];
  bool canMapBuffers_ = true;
  int submitIndex_ = 0;
  int inFlight_ = 0;
  bool isRewinding_ = false;

  usbdevfs_urb *feedbackUrb_ = nullptr;
  uint8_t feedbackBuffer_[4] = {};
  bool feedbackInFlight_ = false;

  uint8_t *ring_ = nullptr;
  int64_t ringCapacityFrames_ = 0;
  std::atomic<uint64_t> writeFrame_{0};
  std::atomic<uint64_t> committedFrame_{0};
  uint64_t submitFrame_ = 0;

  uint32_t ditherState_ = 0x9E3779B9u;
  std::atomic<float> gain_{1.0f};

  std::atomic<int64_t> playedFrames_{0};
  std::atomic<bool> playing_{false};
  std::atomic<bool> endOfStream_{false};
  std::atomic<bool> released_{false};
  std::atomic<int> errorCode_{0};

  StallStats stallStats_;
  Clock::time_point lastReapTime_;
  Clock::time_point lastStallReportTime_;
  bool hasReapTime_ = false;

  std::mutex mutex_;
  std::condition_variable condition_;
  std::thread thread_;

  /// usbfs memory is handed to the controller as is, a plain buffer costs a kernel allocation and a copy on every submit.
  bool allocateBuffer(Slot &slot, size_t bytes) {
    if (canMapBuffers_) {
      void *mapped = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
      if (mapped != MAP_FAILED) {
        slot.buffer = static_cast<uint8_t *>(mapped);
        slot.mappedBytes = bytes;
        return true;
      }
      canMapBuffers_ = false;
    }
    slot.buffer = static_cast<uint8_t *>(malloc(bytes));
    return slot.buffer != nullptr;
  }

  /// the mutex orders the notification after the waiter's own check, so it's never lost between the two.
  void wake() {
    { std::lock_guard<std::mutex> lock(mutex_); }
    condition_.notify_one();
  }

  void run() {
    if (setpriority(PRIO_PROCESS, 0, kUrgentAudioPriority) != 0) LOGW("streaming thread priority unchanged, errno=%d", errno);
    while (!released_.load() && errorCode_.load() == 0) {
      const bool isPlaying = playing_.load();
      if (isPlaying && !isRewinding_) fillUrbs();
      if (!isPlaying && inFlight_ > 0 && !isRewinding_) discardQueued();
      if (inFlight_ > 0 || feedbackInFlight_) {
        if (!reapOne()) break;
        continue;
      }
      hasReapTime_ = false;
      std::unique_lock<std::mutex> lock(mutex_);
      condition_.wait_for(lock, std::chrono::milliseconds(100), [this] { return hasWork(); });
    }
    drainAfterRelease();
  }

  int64_t queuedFrames() const { return static_cast<int64_t>(writeFrame_.load(std::memory_order_acquire) - submitFrame_); }

  bool hasWork() const { return released_.load() || (playing_.load() && queuedFrames() > 0); }

  void fillUrbs() {
    while (inFlight_ < kUrbCount && !released_.load()) {
      Slot &slot = slots_[submitIndex_];
      if (slot.inFlight) return;

      double accumulator = frameAccumulator_;
      int packetFrames[kMaxPacketsPerUrb];
      int64_t urbFrames = 0;
      int64_t clampedFrames = 0;
      for (int p = 0; p < packetsPerUrb_; p++) {
        accumulator += framesPerPacket_;
        const int wholeFrames = static_cast<int>(accumulator);
        accumulator -= wholeFrames;
        // -- what a tight endpoint can't take is dropped like snd-usb-audio does, carrying it over would only burst later
        const int frames = wholeFrames < maxFramesPerPacket_ ? wholeFrames : maxFramesPerPacket_;
        clampedFrames += wholeFrames - frames;
        packetFrames[p] = frames;
        urbFrames += frames;
      }

      const int64_t availableFrames = queuedFrames();
      int64_t audioFrames;
      if (availableFrames >= urbFrames) {
        audioFrames = urbFrames;
      } else if (endOfStream_.load()) {
        if (availableFrames == 0) return;
        audioFrames = availableFrames;
      } else if (inFlight_ <= kUnderrunUrbs) {
        audioFrames = availableFrames;
        stallStats_.starvedFrames += urbFrames - audioFrames;
      } else {
        return;
      }

      fillBuffer(slot.buffer, audioFrames, urbFrames);

      usbdevfs_urb *urb = slot.urb;
      memset(urb, 0, sizeof(usbdevfs_urb) + packetsPerUrb_ * sizeof(usbdevfs_iso_packet_desc));
      urb->type = USBDEVFS_URB_TYPE_ISO;
      urb->flags = USBDEVFS_URB_ISO_ASAP;
      urb->endpoint = static_cast<unsigned char>(endpoint_);
      urb->buffer = slot.buffer;
      urb->buffer_length = static_cast<int>(urbFrames * bytesPerFrame_);
      urb->number_of_packets = packetsPerUrb_;
      for (int p = 0; p < packetsPerUrb_; p++) {
        urb->iso_frame_desc[p].length = static_cast<unsigned int>(packetFrames[p] * bytesPerFrame_);
      }
      const Clock::time_point submitStart = Clock::now();
      if (ioctl(fd_, USBDEVFS_SUBMITURB, urb) < 0) {
        fail(errno);
        return;
      }
      const int64_t submitUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - submitStart).count();
      stallStats_.slowestSubmitUs = std::max(stallStats_.slowestSubmitUs, submitUs);
      stallStats_.clampedFrames += clampedFrames;
      frameAccumulator_ = accumulator;
      submitFrame_ += static_cast<uint64_t>(audioFrames);
      slot.audioFrames = audioFrames;
      slot.inFlight = true;
      inFlight_++;
      submitIndex_ = (submitIndex_ + 1) % kUrbCount;
      if (!feedbackInFlight_) submitFeedback();
    }
  }

  /// copies or converts [audioFrames] from the ring at the submit position, the rest of the urb is silence.
  void fillBuffer(uint8_t *out, int64_t audioFrames, int64_t urbFrames) {
    uint64_t frame = submitFrame_;
    int64_t remaining = audioFrames;
    while (remaining > 0) {
      const int64_t ringIndex = static_cast<int64_t>(frame % static_cast<uint64_t>(ringCapacityFrames_));
      const int64_t frames = std::min(remaining, ringCapacityFrames_ - ringIndex);
      const uint8_t *source = ring_ + ringIndex * inputFrameBytes_;
      if (isPassthrough_) {
        memcpy(out, source, static_cast<size_t>(frames * bytesPerFrame_));
      } else {
        convert(source, frames, out);
      }
      out += frames * bytesPerFrame_;
      frame += static_cast<uint64_t>(frames);
      remaining -= frames;
    }
    memset(out, 0, static_cast<size_t>((urbFrames - audioFrames) * bytesPerFrame_));
  }

  /// a paused stream stops right away instead of playing out the queue, the unplayed audio is rewound once every urb is back.
  void discardQueued() {
    for (Slot &slot : slots_) {
      if (slot.inFlight) ioctl(fd_, USBDEVFS_DISCARDURB, slot.urb);
    }
    isRewinding_ = true;
  }

  float nextDither() {
    ditherState_ ^= ditherState_ << 13;
    ditherState_ ^= ditherState_ >> 17;
    ditherState_ ^= ditherState_ << 5;
    return static_cast<float>(ditherState_) * (1.0f / 4294967296.0f);
  }

  static float readInput(const uint8_t *sample, const Conversion &c) {
    if (c.inputFloat) {
      float value;
      memcpy(&value, sample, sizeof(float));
      return value;
    }
    switch (c.inputBytesPerSample) {
      case 2:
        return static_cast<int16_t>(sample[0] | (sample[1] << 8)) * (1.0f / 32768.0f);
      case 3:
        return static_cast<float>(static_cast<int32_t>((sample[0] << 8) | (sample[1] << 16) | (sample[2] << 24)) >> 8) * (1.0f / 8388608.0f);
      default:
        return static_cast<float>(static_cast<int32_t>(sample[0] | (sample[1] << 8) | (sample[2] << 16) | (sample[3] << 24))) * (1.0f / 2147483648.0f);
    }
  }

  /// mono is duplicated, surround keeps its front pair with center and surrounds folded in at -3 dB.
  void convert(const uint8_t *input, int64_t frames, uint8_t *out) {
    const Conversion &c = conversion_;
    const bool isIntegerCopy = !c.inputFloat && c.inputBytesPerSample == c.subslotBytes;
    const float fullScale = static_cast<float>(1LL << (c.resolution - 1));
    const int64_t maxValue = (1LL << (c.resolution - 1)) - 1;
    const int64_t minValue = -(1LL << (c.resolution - 1));
    const int shift = c.subslotBytes * 8 - c.resolution;
    const bool isSurroundFold = c.inputChannels >= 6 && c.outputChannels == 2;
    const float foldScale = isSurroundFold ? 1.0f / 2.4142f : 1.0f;
    const float gain = gain_.load(std::memory_order_relaxed);
    for (int64_t f = 0; f < frames; f++) {
      const uint8_t *frame = input + f * inputFrameBytes_;
      for (int ch = 0; ch < c.outputChannels; ch++) {
        const int sourceChannel = c.inputChannels == 1 ? 0 : (ch < c.inputChannels ? ch : -1);
        if (isIntegerCopy && sourceChannel >= 0 && !isSurroundFold) {
          memcpy(out, frame + sourceChannel * c.inputBytesPerSample, static_cast<size_t>(c.subslotBytes));
          out += c.subslotBytes;
          continue;
        }
        float value = sourceChannel >= 0 ? readInput(frame + sourceChannel * c.inputBytesPerSample, c) : 0.0f;
        if (isSurroundFold && ch < 2) {
          const float center = readInput(frame + 2 * c.inputBytesPerSample, c);
          const float surround = readInput(frame + (4 + ch) * c.inputBytesPerSample, c);
          value = (value + 0.7071f * center + 0.7071f * surround) * foldScale;
        }
        // -- integer input widened into a bigger container is exact, dither would only add noise to it
        const float dither = c.inputFloat ? nextDither() - nextDither() : 0.0f;
        int64_t quantized = static_cast<int64_t>(lrintf(value * gain * fullScale + dither));
        if (quantized > maxValue) quantized = maxValue;
        if (quantized < minValue) quantized = minValue;
        const int64_t aligned = quantized << shift;
        for (int b = 0; b < c.subslotBytes; b++) {
          *out++ = static_cast<uint8_t>(aligned >> (8 * b));
        }
      }
    }
  }

  void submitFeedback() {
    if (feedbackUrb_ == nullptr || released_.load()) return;
    memset(feedbackUrb_, 0, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc));
    feedbackUrb_->type = USBDEVFS_URB_TYPE_ISO;
    feedbackUrb_->flags = USBDEVFS_URB_ISO_ASAP;
    feedbackUrb_->endpoint = static_cast<unsigned char>(feedbackEndpoint_);
    feedbackUrb_->buffer = feedbackBuffer_;
    feedbackUrb_->buffer_length = feedbackBytes_;
    feedbackUrb_->number_of_packets = 1;
    feedbackUrb_->iso_frame_desc[0].length = static_cast<unsigned int>(feedbackBytes_);
    feedbackInFlight_ = ioctl(fd_, USBDEVFS_SUBMITURB, feedbackUrb_) == 0;
  }

  /// blocks until any urb completes.
  bool reapOne() {
    usbdevfs_urb *completed = nullptr;
    int result;
    do {
      result = ioctl(fd_, USBDEVFS_REAPURB, &completed);
    } while (result < 0 && errno == EINTR);
    if (result < 0) {
      fail(errno);
      return false;
    }
    if (completed == feedbackUrb_) {
      feedbackInFlight_ = false;
      onFeedback(completed);
      if (inFlight_ > 0 || playing_.load()) submitFeedback();
      return true;
    }
    for (Slot &slot : slots_) {
      if (slot.urb != completed) continue;
      onDataReaped(slot);
      break;
    }
    return true;
  }

  void onDataReaped(Slot &slot) {
    const usbdevfs_urb *urb = slot.urb;
    int64_t played = slot.audioFrames;
    if (isRewinding_ && urb->status != 0) {
      int64_t sentBytes = 0;
      for (int p = 0; p < urb->number_of_packets; p++) {
        if (urb->iso_frame_desc[p].status == 0) sentBytes += urb->iso_frame_desc[p].actual_length;
      }
      played = std::min(played, sentBytes / bytesPerFrame_);
    } else {
      for (int p = 0; p < urb->number_of_packets; p++) {
        if (urb->iso_frame_desc[p].status != 0) stallStats_.missedPackets++;
      }
      trackReapGap();
    }
    slot.inFlight = false;
    inFlight_--;
    committedFrame_.store(committedFrame_.load(std::memory_order_relaxed) + static_cast<uint64_t>(played), std::memory_order_release);
    playedFrames_.fetch_add(played, std::memory_order_release);
    if (isRewinding_ && inFlight_ == 0) {
      submitFrame_ = committedFrame_.load(std::memory_order_relaxed);
      isRewinding_ = false;
      hasReapTime_ = false;
    }
  }

  void trackReapGap() {
    const Clock::time_point now = Clock::now();
    if (hasReapTime_) {
      const int64_t gapUs = std::chrono::duration_cast<std::chrono::microseconds>(now - lastReapTime_).count();
      stallStats_.longestReapGapUs = std::max(stallStats_.longestReapGapUs, gapUs);
    }
    lastReapTime_ = now;
    hasReapTime_ = true;
    const int64_t sinceReportUs = std::chrono::duration_cast<std::chrono::microseconds>(now - lastStallReportTime_).count();
    if (sinceReportUs < kStallReportIntervalUs) return;
    if (stallStats_.hasStalls(urbDurationUs_)) {
      LOGW("stalls: starved %lld frames, clamped %lld frames, missed %d packets, longest reap gap %lld us, slowest submit %lld us",
           static_cast<long long>(stallStats_.starvedFrames), static_cast<long long>(stallStats_.clampedFrames), stallStats_.missedPackets,
           static_cast<long long>(stallStats_.longestReapGapUs), static_cast<long long>(stallStats_.slowestSubmitUs));
    }
    stallStats_ = StallStats();
    lastStallReportTime_ = now;
  }

  /// the format differs between devices (10.14 or 16.16, per frame, microframe or packet), always a power of two apart, so like
  /// snd-usb-audio the first value is shifted until it lands near the nominal rate, and that shift sticks while values stay plausible.
  void onFeedback(const usbdevfs_urb *urb) {
    if (urb->status != 0 || urb->iso_frame_desc[0].actual_length < 3) return;
    uint32_t raw = feedbackBuffer_[0] | (feedbackBuffer_[1] << 8) | (feedbackBuffer_[2] << 16);
    if (urb->iso_frame_desc[0].actual_length >= 4) raw |= static_cast<uint32_t>(feedbackBuffer_[3]) << 24;
    if (raw == 0) return;
    double framesPerPacket = raw / 65536.0;
    if (feedbackShift_ == kUnknownFeedbackShift) {
      int shift = 0;
      while (framesPerPacket < nominalFramesPerPacket_ * 0.75 && shift < 24) {
        framesPerPacket *= 2.0;
        shift++;
      }
      while (framesPerPacket >= nominalFramesPerPacket_ * 1.5 && shift > -24) {
        framesPerPacket *= 0.5;
        shift--;
      }
      feedbackShift_ = shift;
      LOGI("feedback: raw 0x%x, shift %d, %.4f frames/packet, nominal %.4f", raw, shift, framesPerPacket, nominalFramesPerPacket_);
    } else {
      framesPerPacket = std::ldexp(framesPerPacket, feedbackShift_);
    }
    const bool isPlausible = framesPerPacket >= nominalFramesPerPacket_ * 0.875 && framesPerPacket <= nominalFramesPerPacket_ * 1.125;
    if (isPlausible) {
      framesPerPacket_ = framesPerPacket;
    } else {
      feedbackShift_ = kUnknownFeedbackShift;
    }
  }

  void fail(int error) {
    if (errorCode_.load() == 0) {
      errorCode_.store(error == 0 ? EIO : error);
      LOGW("stream failed, errno=%d (%s)", error, strerror(error));
    }
  }

  void drainAfterRelease() {
    for (int attempt = 0; attempt < 200 && (inFlight_ > 0 || feedbackInFlight_); attempt++) {
      usbdevfs_urb *completed = nullptr;
      if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &completed) == 0 && completed != nullptr) {
        if (completed == feedbackUrb_) {
          feedbackInFlight_ = false;
          continue;
        }
        for (Slot &slot : slots_) {
          if (slot.urb != completed) continue;
          slot.inFlight = false;
          inFlight_--;
          break;
        }
        continue;
      }
      if (errno == ENODEV) return;
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
  }
};

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativeGetSpeed(JNIEnv *, jclass, jint fd) {
  return ioctl(fd, USBDEVFS_GET_SPEED);
}

JNIEXPORT jlong JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativeCreate(JNIEnv *, jclass, jint fd, jint endpoint, jint feedbackEndpoint,
                                                                                    jint feedbackBytes, jint packetsPerSecond, jint sampleRate,
                                                                                    jboolean inputFloat, jint inputChannels, jint inputBytesPerSample,
                                                                                    jint outputChannels, jint subslotBytes, jint resolution,
                                                                                    jint maxPacketBytes) {
  const Conversion conversion{inputFloat == JNI_TRUE, inputChannels, inputBytesPerSample, outputChannels, subslotBytes, resolution};
  auto *stream = new UsbAudioStream(fd, endpoint, feedbackEndpoint, feedbackBytes, packetsPerSecond, sampleRate, conversion, maxPacketBytes);
  if (!stream->init()) {
    stream->release();
    delete stream;
    return 0;
  }
  return reinterpret_cast<jlong>(stream);
}

JNIEXPORT jint JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativeWriteDirect(JNIEnv *env, jclass, jlong handle, jobject buffer, jint offset,
                                                                                        jint length) {
  auto *data = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
  if (data == nullptr) return 0;
  return reinterpret_cast<UsbAudioStream *>(handle)->write(data + offset, length);
}

JNIEXPORT jint JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativeWriteArray(JNIEnv *env, jclass, jlong handle, jbyteArray array, jint offset,
                                                                                       jint length) {
  auto *data = static_cast<uint8_t *>(env->GetPrimitiveArrayCritical(array, nullptr));
  if (data == nullptr) return 0;
  const int written = reinterpret_cast<UsbAudioStream *>(handle)->write(data + offset, length);
  env->ReleasePrimitiveArrayCritical(array, data, JNI_ABORT);
  return written;
}

JNIEXPORT void JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativeSetPlaying(JNIEnv *, jclass, jlong handle, jboolean playing) {
  reinterpret_cast<UsbAudioStream *>(handle)->setPlaying(playing == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativeEndOfStream(JNIEnv *, jclass, jlong handle) {
  reinterpret_cast<UsbAudioStream *>(handle)->endOfStream();
}

JNIEXPORT void JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativeSetGain(JNIEnv *, jclass, jlong handle, jfloat gain) {
  reinterpret_cast<UsbAudioStream *>(handle)->setGain(gain);
}

JNIEXPORT jlong JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativePlayedFrames(JNIEnv *, jclass, jlong handle) {
  return reinterpret_cast<UsbAudioStream *>(handle)->playedFrames();
}

JNIEXPORT jint JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativeErrorCode(JNIEnv *, jclass, jlong handle) {
  return reinterpret_cast<UsbAudioStream *>(handle)->errorCode();
}

JNIEXPORT void JNICALL Java_com_ryanheise_just_1audio_UsbAudioStream_nativeRelease(JNIEnv *, jclass, jlong handle) {
  auto *stream = reinterpret_cast<UsbAudioStream *>(handle);
  stream->release();
  delete stream;
}

}  // extern "C"
