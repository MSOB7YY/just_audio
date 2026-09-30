// by claude
package com.ryanheise.just_audio;

import java.nio.ByteBuffer;

/// one native isochronous output stream on a claimed usb audio interface.
final class UsbAudioStream {

  static final int SPEED_HIGH = 3;

  static {
    System.loadLibrary("just_audio_usb");
  }

  private final long handle;

  private UsbAudioStream(long handle) {
    this.handle = handle;
  }

  /// null when the format can't fit the endpoint's packets or memory ran out.
  static UsbAudioStream create(int fd, UsbAudioDescriptors.Format format, boolean isHighSpeed, int sampleRate, boolean inputFloat,
      int inputChannels, int inputBytesPerSample) {
    final int serviceUnitsPerSecond = isHighSpeed ? 8000 : 1000;
    final int intervalShift = isHighSpeed ? Math.max(0, format.interval - 1) : 0;
    final int packetsPerSecond = serviceUnitsPerSecond >> intervalShift;
    // -- the feedback endpoint's own packet size, a bigger request than it takes errors on some controllers
    final int defaultFeedbackBytes = isHighSpeed ? 4 : 3;
    final int feedbackBytes = format.feedbackMaxPacketBytes > 0 ? Math.min(4, format.feedbackMaxPacketBytes) : defaultFeedbackBytes;
    final long handle = nativeCreate(fd, format.endpoint, format.feedbackEndpoint, feedbackBytes, packetsPerSecond, sampleRate, inputFloat,
        inputChannels, inputBytesPerSample, format.channels, format.subslotBytes, format.bitResolution, format.maxPacketBytes);
    return handle == 0 ? null : new UsbAudioStream(handle);
  }

  static boolean isHighSpeed(int fd) {
    return nativeGetSpeed(fd) >= SPEED_HIGH;
  }

  /// returns the bytes consumed from [buffer] starting at its position.
  int write(ByteBuffer buffer) {
    final int position = buffer.position();
    final int remaining = buffer.remaining();
    if (buffer.isDirect()) return nativeWriteDirect(handle, buffer, position, remaining);
    return nativeWriteArray(handle, buffer.array(), buffer.arrayOffset() + position, remaining);
  }

  void setPlaying(boolean playing) {
    nativeSetPlaying(handle, playing);
  }

  void endOfStream() {
    nativeEndOfStream(handle);
  }

  /// scales float input before it's quantized, integer input stays untouched.
  void setGain(float gain) {
    nativeSetGain(handle, gain);
  }

  long getPlayedFrames() {
    return nativePlayedFrames(handle);
  }

  int getErrorCode() {
    return nativeErrorCode(handle);
  }

  void release() {
    nativeRelease(handle);
  }

  private static native int nativeGetSpeed(int fd);

  private static native long nativeCreate(int fd, int endpoint, int feedbackEndpoint, int feedbackBytes, int packetsPerSecond, int sampleRate,
      boolean inputFloat, int inputChannels, int inputBytesPerSample, int outputChannels, int subslotBytes, int resolution, int maxPacketBytes);

  private static native int nativeWriteDirect(long handle, ByteBuffer buffer, int offset, int length);

  private static native int nativeWriteArray(long handle, byte[] array, int offset, int length);

  private static native void nativeSetPlaying(long handle, boolean playing);

  private static native void nativeEndOfStream(long handle);

  private static native void nativeSetGain(long handle, float gain);

  private static native long nativePlayedFrames(long handle);

  private static native int nativeErrorCode(long handle);

  private static native void nativeRelease(long handle);
}
