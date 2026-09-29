// by claude
package com.ryanheise.just_audio;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;

import java.util.HashMap;
import java.util.Map;

/// what the last configured player feeds through: its source, decoder, the sink's input and the output it plays on.
/// written from the main and playback threads.
final class AudioSignalPath {

  static final String OUTPUT_ANDROID_MIXER = "android_mixer";
  static final String OUTPUT_BIT_PERFECT_MIXER = "bit_perfect_mixer";
  static final String OUTPUT_USB_DIRECT = "usb_direct";

  @Nullable private String sourceMime;
  private int sourceSampleRate;
  private int sourceChannels;
  private int sourceBitDepth;
  private int sourceBitrate;

  @Nullable private String decoderName;

  private int decodedSampleRate;
  private int decodedChannels;
  private int decodedBitDepth;
  private boolean isDecodedFloat;
  private boolean isBitPerfect;

  @Nullable private String output;
  @Nullable private String outputDeviceName;
  private int outputSampleRate;
  private int outputChannels;
  private int outputBitDepth;
  private boolean isOutputFloat;
  private boolean isOutputDithered;
  private boolean hasOutputHardwareVolume;
  private int mixerSampleRate;

  synchronized void setSource(Format format) {
    sourceMime = format.sampleMimeType;
    sourceSampleRate = format.sampleRate;
    sourceChannels = format.channelCount;
    sourceBitDepth = bitDepthOf(format.pcmEncoding);
    sourceBitrate = format.bitrate;
  }

  synchronized void setDecoder(String name) {
    decoderName = name;
  }

  synchronized void setSinkInput(Format decoded, boolean isBitPerfect) {
    decodedSampleRate = decoded.sampleRate;
    decodedChannels = decoded.channelCount;
    decodedBitDepth = bitDepthOf(decoded.pcmEncoding);
    isDecodedFloat = decoded.pcmEncoding == C.ENCODING_PCM_FLOAT;
    this.isBitPerfect = isBitPerfect;
  }

  /// [mixerSampleRate] is android's own output rate, 0 when the mixer is bypassed.
  synchronized void setAndroidOutput(boolean isBitPerfectMixer, @Nullable String deviceName, int sampleRate, int channels,
      @C.PcmEncoding int encoding, int mixerSampleRate) {
    output = isBitPerfectMixer ? OUTPUT_BIT_PERFECT_MIXER : OUTPUT_ANDROID_MIXER;
    outputDeviceName = deviceName;
    outputSampleRate = sampleRate;
    outputChannels = channels;
    outputBitDepth = bitDepthOf(encoding);
    isOutputFloat = encoding == C.ENCODING_PCM_FLOAT;
    isOutputDithered = false;
    hasOutputHardwareVolume = false;
    this.mixerSampleRate = mixerSampleRate;
  }

  synchronized void setUsbOutput(String deviceName, int sampleRate, int channels, int bitDepth, boolean isDithered, boolean hasHardwareVolume) {
    output = OUTPUT_USB_DIRECT;
    outputDeviceName = deviceName;
    outputSampleRate = sampleRate;
    outputChannels = channels;
    outputBitDepth = bitDepth;
    isOutputFloat = false;
    isOutputDithered = isDithered;
    hasOutputHardwareVolume = hasHardwareVolume;
    mixerSampleRate = 0;
  }

  synchronized Map<String, Object> toMap() {
    final Map<String, Object> map = new HashMap<>();
    map.put("sourceMime", sourceMime);
    map.put("sourceSampleRate", sourceSampleRate);
    map.put("sourceChannels", sourceChannels);
    map.put("sourceBitDepth", sourceBitDepth);
    map.put("sourceBitrate", sourceBitrate);
    map.put("decoderName", decoderName);
    map.put("decodedSampleRate", decodedSampleRate);
    map.put("decodedChannels", decodedChannels);
    map.put("decodedBitDepth", decodedBitDepth);
    map.put("decodedFloat", isDecodedFloat);
    map.put("bitPerfect", isBitPerfect);
    map.put("output", output);
    map.put("outputDeviceName", outputDeviceName);
    map.put("outputSampleRate", outputSampleRate);
    map.put("outputChannels", outputChannels);
    map.put("outputBitDepth", outputBitDepth);
    map.put("outputFloat", isOutputFloat);
    map.put("outputDithered", isOutputDithered);
    map.put("outputHardwareVolume", hasOutputHardwareVolume);
    map.put("mixerSampleRate", mixerSampleRate);
    return map;
  }

  private static int bitDepthOf(int pcmEncoding) {
    switch (pcmEncoding) {
      case C.ENCODING_PCM_8BIT:
        return 8;
      case C.ENCODING_PCM_16BIT:
      case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
        return 16;
      case C.ENCODING_PCM_24BIT:
      case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
        return 24;
      case C.ENCODING_PCM_32BIT:
      case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_FLOAT:
        return 32;
      default:
        return 0;
    }
  }
}
