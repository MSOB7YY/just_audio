package com.ryanheise.just_audio;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessorChain;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor;

/// silence skipping -> speed/pitch -> equalizer -> sound effects -> usb dac rate conversion, so the equalizer and effects shape
/// what is actually heard and run at the track's own rate.
@UnstableApi
final class PlaybackAudioProcessorChain implements AudioProcessorChain {

  private final SilenceSkippingAudioProcessor silenceSkippingAudioProcessor;
  private final SonicAudioProcessor sonicAudioProcessor;
  private final UsbRateConverterAudioProcessor usbRateConverterAudioProcessor;
  private final AudioProcessor[] audioProcessors;

  PlaybackAudioProcessorChain(SilenceSkippingAudioProcessor silenceSkippingAudioProcessor,
      UsbRateConverterAudioProcessor usbRateConverterAudioProcessor) {
    this.silenceSkippingAudioProcessor = silenceSkippingAudioProcessor;
    this.sonicAudioProcessor = new SonicAudioProcessor();
    this.usbRateConverterAudioProcessor = usbRateConverterAudioProcessor;
    this.audioProcessors = new AudioProcessor[] {
        silenceSkippingAudioProcessor,
        sonicAudioProcessor,
        new ParametricEqualizerAudioProcessor(),
        new SoundEffectsAudioProcessor(),
        usbRateConverterAudioProcessor,
    };
  }

  @Override
  public AudioProcessor[] getAudioProcessors() {
    return audioProcessors;
  }

  @Override
  public PlaybackParameters applyPlaybackParameters(PlaybackParameters playbackParameters) {
    sonicAudioProcessor.setSpeed(playbackParameters.speed);
    sonicAudioProcessor.setPitch(playbackParameters.pitch);
    return playbackParameters;
  }

  @Override
  public boolean applySkipSilenceEnabled(boolean skipSilenceEnabled) {
    silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled);
    return skipSilenceEnabled;
  }

  @Override
  public long getMediaDuration(long playoutDuration) {
    return sonicAudioProcessor.isActive() ? sonicAudioProcessor.getMediaDuration(playoutDuration) : playoutDuration;
  }

  @Override
  public long getSkippedOutputFrameCount() {
    final long skippedFrames = silenceSkippingAudioProcessor.getSkippedFrames();
    return usbRateConverterAudioProcessor.toOutputFrameCount(skippedFrames);
  }
}
