// by claude, streaming model based on decent-player's usb-audio-output (MIT, see LICENSE-decent-player)
//
// PCM written by the player lands in a lock-free ring, a dedicated thread moves it into isochronous URBs sized by
// the DAC's own clock (async feedback endpoint). Integer input in the DAC's own format is copied untouched, float input
// (tracks the DAC can't take losslessly, already resampled) is quantized to the DAC's resolution with TPDF dither.

#include <jni.h>

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>

#define LOG_TAG "UsbAudioStream"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// -- 128 ms in flight, the streaming thread isn't realtime and app launches can hold it off for a while (#1264)
constexpr int kUrbCount = 32;
constexpr int kUrbDurationMs = 4;
constexpr int kMaxPacketsPerUrb = 32;
constexpr int kUnderrunUrbs = 2;
constexpr int kRingDurationMs = 250;
constexpr int kScratchFrames = 4096;
constexpr int kUrgentAudioPriority = -19;

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
  int64_t frames = 0;
  bool inFlight = false;
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
        conversion_(conversion),
        bytesPerFrame_(conversion.outputFrameBytes()),
        maxPacketBytes_(maxPacketBytes),
        nominalFramesPerPacket_(static_cast<double>(sampleRate) / packetsPerSecond),
        framesPerPacket_(nominalFramesPerPacket_) {
    const int packets = packetsPerSecond * kUrbDurationMs / 1000;
    packetsPerUrb_ = packets < 1 ? 1 : (packets > kMaxPacketsPerUrb ? kMaxPacketsPerUrb : packets);
    const int64_t ringFrames = static_cast<int64_t>(sampleRate) * kRingDurationMs / 1000;
    ringCapacity_ = static_cast<size_t>(ringFrames * bytesPerFrame_);
  }

  ~UsbAudioStream() {
    for (Slot &slot : slots_) {
      free(slot.urb);
      free(slot.buffer);
    }
    free(feedbackUrb_);
    free(ring_);
    free(scratch_);
  }

  bool init() {
    // -- packets are clamped to what the endpoint takes, like snd-usb-audio does, so a tightly sized endpoint still streams
    maxFramesPerPacket_ = maxPacketBytes_ / bytesPerFrame_;
    if (static_cast<int>(std::ceil(nominalFramesPerPacket_)) > maxFramesPerPacket_) return false;
    const size_t urbSize = sizeof(usbdevfs_urb) + packetsPerUrb_ * sizeof(usbdevfs_iso_packet_desc);
    const size_t bufferSize = static_cast<size_t>(packetsPerUrb_) * maxFramesPerPacket_ * bytesPerFrame_;
    for (Slot &slot : slots_) {
      slot.urb = static_cast<usbdevfs_urb *>(calloc(1, urbSize));
      slot.buffer = static_cast<uint8_t *>(malloc(bufferSize));
      if (slot.urb == nullptr || slot.buffer == nullptr) return false;
    }
    if (feedbackEndpoint_ > 0) {
      feedbackUrb_ = static_cast<usbdevfs_urb *>(calloc(1, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc)));
      if (feedbackUrb_ == nullptr) return false;
    }
    ring_ = static_cast<uint8_t *>(malloc(ringCapacity_));
    if (ring_ == nullptr) return false;
    if (!conversion_.isPassthrough()) {
      scratch_ = static_cast<uint8_t *>(malloc(static_cast<size_t>(kScratchFrames) * bytesPerFrame_));
      if (scratch_ == nullptr) return false;
    }
    thread_ = std::thread(&UsbAudioStream::run, this);
    return true;
  }

  /// returns the input bytes consumed, whole frames only.
  int write(const uint8_t *data, int bytes) {
    const int inputFrameBytes = conversion_.inputFrameBytes();
    int consumed = 0;
    while (bytes - consumed >= inputFrameBytes) {
      const uint64_t writePos = writePos_.load(std::memory_order_relaxed);
      const uint64_t readPos = readPos_.load(std::memory_order_acquire);
      const int64_t freeFrames = static_cast<int64_t>(ringCapacity_ - static_cast<size_t>(writePos - readPos)) / bytesPerFrame_;
      int64_t frames = (bytes - consumed) / inputFrameBytes;
      if (frames > freeFrames) frames = freeFrames;
      if (frames <= 0) break;
      const uint8_t *source = data + consumed;
      if (conversion_.isPassthrough()) {
        writeToRing(source, static_cast<size_t>(frames * bytesPerFrame_));
      } else {
        if (frames > kScratchFrames) frames = kScratchFrames;
        convert(source, static_cast<int>(frames));
        writeToRing(scratch_, static_cast<size_t>(frames * bytesPerFrame_));
      }
      consumed += static_cast<int>(frames * inputFrameBytes);
    }
    if (consumed > 0) {
      endOfStream_.store(false);
      wake();
    }
    return consumed;
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

  int64_t bufferedFrames() const {
    const uint64_t writePos = writePos_.load(std::memory_order_acquire);
    const uint64_t readPos = readPos_.load(std::memory_order_acquire);
    return static_cast<int64_t>((writePos - readPos) / bytesPerFrame_);
  }

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
  const Conversion conversion_;
  const int bytesPerFrame_;
  const int maxPacketBytes_;
  const double nominalFramesPerPacket_;
  int maxFramesPerPacket_ = 0;
  double framesPerPacket_;
  double frameAccumulator_ = 0.0;
  int packetsPerUrb_ = 1;

  Slot slots_[kUrbCount];
  int submitIndex_ = 0;
  int inFlight_ = 0;

  usbdevfs_urb *feedbackUrb_ = nullptr;
  uint8_t feedbackBuffer_[4] = {};
  bool feedbackInFlight_ = false;

  uint8_t *ring_ = nullptr;
  uint8_t *scratch_ = nullptr;
  uint32_t ditherState_ = 0x9E3779B9u;
  std::atomic<float> gain_{1.0f};
  size_t ringCapacity_ = 0;
  std::atomic<uint64_t> writePos_{0};
  std::atomic<uint64_t> readPos_{0};

  std::atomic<int64_t> playedFrames_{0};
  std::atomic<bool> playing_{false};
  std::atomic<bool> endOfStream_{false};
  std::atomic<bool> released_{false};
  std::atomic<int> errorCode_{0};

  std::mutex mutex_;
  std::condition_variable condition_;
  std::thread thread_;

  void wake() { condition_.notify_one(); }

  void run() {
    setpriority(PRIO_PROCESS, 0, kUrgentAudioPriority);
    while (!released_.load() && errorCode_.load() == 0) {
      if (playing_.load()) fillUrbs();
      if (inFlight_ > 0 || feedbackInFlight_) {
        if (!reapOne()) break;
        continue;
      }
      std::unique_lock<std::mutex> lock(mutex_);
      condition_.wait_for(lock, std::chrono::milliseconds(100), [this] { return hasWork(); });
    }
    drainAfterRelease();
  }

  bool hasWork() const { return released_.load() || (playing_.load() && bufferedFrames() > 0); }

  void fillUrbs() {
    while (inFlight_ < kUrbCount && !released_.load()) {
      Slot &slot = slots_[submitIndex_];
      if (slot.inFlight) return;

      double accumulator = frameAccumulator_;
      int packetFrames[kMaxPacketsPerUrb];
      int64_t urbFrames = 0;
      for (int p = 0; p < packetsPerUrb_; p++) {
        accumulator += framesPerPacket_;
        int frames = static_cast<int>(accumulator);
        if (frames > maxFramesPerPacket_) frames = maxFramesPerPacket_;
        accumulator -= frames;
        packetFrames[p] = frames;
        urbFrames += frames;
      }

      const int64_t availableFrames = bufferedFrames();
      int64_t audioFrames;
      if (availableFrames >= urbFrames) {
        audioFrames = urbFrames;
      } else if (endOfStream_.load()) {
        if (availableFrames == 0) return;
        audioFrames = availableFrames;
      } else if (inFlight_ <= kUnderrunUrbs) {
        audioFrames = availableFrames;
      } else {
        return;
      }

      const size_t audioBytes = static_cast<size_t>(audioFrames * bytesPerFrame_);
      const size_t urbBytes = static_cast<size_t>(urbFrames * bytesPerFrame_);
      readFromRing(slot.buffer, audioBytes);
      memset(slot.buffer + audioBytes, 0, urbBytes - audioBytes);

      usbdevfs_urb *urb = slot.urb;
      memset(urb, 0, sizeof(usbdevfs_urb) + packetsPerUrb_ * sizeof(usbdevfs_iso_packet_desc));
      urb->type = USBDEVFS_URB_TYPE_ISO;
      urb->flags = USBDEVFS_URB_ISO_ASAP;
      urb->endpoint = static_cast<unsigned char>(endpoint_);
      urb->buffer = slot.buffer;
      urb->buffer_length = static_cast<int>(urbBytes);
      urb->number_of_packets = packetsPerUrb_;
      for (int p = 0; p < packetsPerUrb_; p++) {
        urb->iso_frame_desc[p].length = static_cast<unsigned int>(packetFrames[p] * bytesPerFrame_);
      }
      if (ioctl(fd_, USBDEVFS_SUBMITURB, urb) < 0) {
        fail(errno);
        return;
      }
      frameAccumulator_ = accumulator;
      slot.frames = audioFrames;
      slot.inFlight = true;
      inFlight_++;
      submitIndex_ = (submitIndex_ + 1) % kUrbCount;
      if (!feedbackInFlight_) submitFeedback();
    }
  }

  void writeToRing(const uint8_t *data, size_t bytes) {
    const uint64_t writePos = writePos_.load(std::memory_order_relaxed);
    const size_t start = static_cast<size_t>(writePos % ringCapacity_);
    const size_t firstPart = bytes < ringCapacity_ - start ? bytes : ringCapacity_ - start;
    memcpy(ring_ + start, data, firstPart);
    memcpy(ring_, data + firstPart, bytes - firstPart);
    writePos_.store(writePos + bytes, std::memory_order_release);
  }

  void readFromRing(uint8_t *out, size_t bytes) {
    if (bytes == 0) return;
    const uint64_t readPos = readPos_.load(std::memory_order_relaxed);
    const size_t start = static_cast<size_t>(readPos % ringCapacity_);
    const size_t firstPart = bytes < ringCapacity_ - start ? bytes : ringCapacity_ - start;
    memcpy(out, ring_ + start, firstPart);
    memcpy(out + firstPart, ring_, bytes - firstPart);
    readPos_.store(readPos + bytes, std::memory_order_release);
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
  void convert(const uint8_t *input, int frames) {
    const Conversion &c = conversion_;
    const bool isIntegerCopy = !c.inputFloat && c.inputBytesPerSample == c.subslotBytes;
    const int inputFrameBytes = c.inputFrameBytes();
    const float fullScale = static_cast<float>(1LL << (c.resolution - 1));
    const int64_t maxValue = (1LL << (c.resolution - 1)) - 1;
    const int64_t minValue = -(1LL << (c.resolution - 1));
    const int shift = c.subslotBytes * 8 - c.resolution;
    const bool isSurroundFold = c.inputChannels >= 6 && c.outputChannels == 2;
    const float foldScale = isSurroundFold ? 1.0f / 2.4142f : 1.0f;
    const float gain = gain_.load(std::memory_order_relaxed);
    uint8_t *out = scratch_;
    for (int f = 0; f < frames; f++) {
      const uint8_t *frame = input + static_cast<size_t>(f) * inputFrameBytes;
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
      slot.inFlight = false;
      inFlight_--;
      playedFrames_.fetch_add(slot.frames, std::memory_order_release);
      break;
    }
    return true;
  }

  /// high speed reports frames per microframe in 16.16, full speed frames per frame in 10.14, some devices mix them up.
  void onFeedback(const usbdevfs_urb *urb) {
    if (urb->status != 0 || urb->iso_frame_desc[0].actual_length < 3) return;
    uint32_t raw = feedbackBuffer_[0] | (feedbackBuffer_[1] << 8) | (feedbackBuffer_[2] << 16);
    if (urb->iso_frame_desc[0].actual_length >= 4) raw |= static_cast<uint32_t>(feedbackBuffer_[3]) << 24;
    const bool isHighSpeedTiming = packetsPerSecond_ > 1000;
    const double perServiceUnit = isHighSpeedTiming ? raw / 65536.0 : raw / 16384.0;
    const double unitsPerPacket = isHighSpeedTiming ? 8000.0 / packetsPerSecond_ : 1000.0 / packetsPerSecond_;
    const double candidates[] = {perServiceUnit, perServiceUnit * 4.0, perServiceUnit / 4.0};
    for (double candidate : candidates) {
      const double framesPerPacket = candidate * unitsPerPacket;
      if (framesPerPacket > nominalFramesPerPacket_ * 0.9 && framesPerPacket < nominalFramesPerPacket_ * 1.1) {
        framesPerPacket_ = framesPerPacket;
        return;
      }
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
