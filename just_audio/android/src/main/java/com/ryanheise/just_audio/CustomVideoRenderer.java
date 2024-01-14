package com.ryanheise.just_audio;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.audio.*;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.metadata.MetadataOutput;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.util.MediaClock;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

public class CustomVideoRenderer extends MediaCodecVideoRenderer implements RenderersFactory {

  private final ExoPlayer audioPlayer;
  private final ExoPlayer videoPlayer;
  private final RenderersFactory renderersFactory;

  public CustomVideoRenderer(final Context context, final ExoPlayer audioPlayer, final ExoPlayer videoPlayer,
      final RenderersFactory renderersFactory) {
    super(context, MediaCodecSelector.DEFAULT);
    this.audioPlayer = audioPlayer;
    this.videoPlayer = videoPlayer;
    this.renderersFactory = renderersFactory;

  }

  @Nullable
  @Override
  public MediaClock getMediaClock() {
    return new MediaClock() {
      @Override
      public long getPositionUs() {
        return audioPlayer.getCurrentPosition();
      }

      @Override
      public void setPlaybackParameters(final PlaybackParameters playbackParameters) {
        videoPlayer.setPlaybackParameters(playbackParameters);
      }

      @Override
      public PlaybackParameters getPlaybackParameters() {
        return videoPlayer.getPlaybackParameters();
      }
    };
  }

  @Override
  public Renderer[] createRenderers(final Handler eventHandler,
      final VideoRendererEventListener videoRendererEventListener,
      final AudioRendererEventListener audioRendererEventListener, final TextOutput textRendererOutput,
      final MetadataOutput metadataRendererOutput) {
    return renderersFactory.createRenderers(eventHandler, videoRendererEventListener, audioRendererEventListener,
        textRendererOutput, metadataRendererOutput);
  }
}
