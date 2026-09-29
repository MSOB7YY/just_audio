// by claude
package com.ryanheise.just_audio;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.AudioOutputProvider;
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider;

/// Routes each sink's bit-perfect decisions through the shared [AudioOutputManager], and its output to a claimed usb dac
/// while usb direct holds one.
@UnstableApi
final class BitPerfectAudioOutputProvider extends ForwardingAudioOutputProvider {

  private static final FormatSupport USB_PCM_SUPPORT = new FormatSupport.Builder().setFormatSupportLevel(FORMAT_SUPPORTED_DIRECTLY).build();

  private final AudioOutputManager outputManager;

  /// whether the sink's current output config was made for the usb dac, the sink always asks for a config before an output.
  private boolean isUsbOutputConfig;

  BitPerfectAudioOutputProvider(AudioOutputProvider audioOutputProvider, AudioOutputManager outputManager) {
    super(audioOutputProvider);
    this.outputManager = outputManager;
  }

  @Override
  public @C.PcmEncoding int getBitPerfectPcmEncoding(FormatConfig formatConfig) {
    final Format format = formatConfig.format;
    final int encoding = outputManager.getBitPerfectPcmEncoding(format);
    outputManager.onSinkInput(format, encoding != C.ENCODING_INVALID);
    return encoding;
  }

  /// the dac only takes PCM, compressed tracks get decoded instead of passed through.
  @Override
  public FormatSupport getFormatSupport(FormatConfig formatConfig) {
    if (outputManager.getUsbDevice() == null) return super.getFormatSupport(formatConfig);
    final boolean isPcm = MimeTypes.AUDIO_RAW.equals(formatConfig.format.sampleMimeType);
    return isPcm ? USB_PCM_SUPPORT : FormatSupport.UNSUPPORTED;
  }

  @Override
  public OutputConfig getOutputConfig(FormatConfig formatConfig) throws ConfigurationException {
    isUsbOutputConfig = outputManager.getUsbDevice() != null;
    if (!isUsbOutputConfig) return super.getOutputConfig(formatConfig);
    final Format format = formatConfig.format;
    final long ringFrames = Util.durationUsToSampleCount(UsbDirectAudioOutput.RING_DURATION_US, format.sampleRate);
    final int frameSize = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount);
    final int bufferSize = (int) ringFrames * frameSize;
    final int channelMask = Util.getAudioTrackChannelConfig(format.channelCount);
    return new OutputConfig.Builder()
        .setEncoding(format.pcmEncoding)
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setBufferSize(bufferSize)
        .setAudioAttributes(formatConfig.audioAttributes)
        .setAudioSessionId(formatConfig.audioSessionId)
        .setVirtualDeviceId(formatConfig.virtualDeviceId)
        .build();
  }

  /// a usb config never falls back to android's output, that would play on the phone's speaker until the players reload.
  @Override
  public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
    if (!isUsbOutputConfig) {
      outputManager.prepareOutput(config);
      return super.getAudioOutput(config);
    }
    @Nullable final AudioOutput output = outputManager.openUsbOutput(config);
    if (output == null) throw new InitializationException();
    return output;
  }
}
