package com.ryanheise.just_audio;

import androidx.media3.exoplayer.source.MediaSource;

import java.util.Map;

public class VideoOptions {
  MediaSource source;
  Boolean videoOnly;
  Boolean loop;

  public VideoOptions(MediaSource source, Boolean videoOnly, Boolean loop) {
    this.source = source;
    this.videoOnly = videoOnly;
    this.loop = loop;
  }

  @SuppressWarnings("unchecked")
  public static VideoOptions fromMap(Map<?, ?> map, MediaSource source) {
    return new VideoOptions(source, (Boolean) map.get("videoOnly"), (Boolean) map.get("loop"));
  }
}
