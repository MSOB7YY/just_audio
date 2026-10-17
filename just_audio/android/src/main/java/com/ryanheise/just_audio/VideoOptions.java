package com.ryanheise.just_audio;

import androidx.media3.exoplayer.source.MediaSource;

import java.util.Map;

public class VideoOptions {
  MediaSource source;
  Boolean videoOnly;
  Boolean loop;
  long startOffsetUs;

  public VideoOptions(MediaSource source, Boolean videoOnly, Boolean loop, long startOffsetUs) {
    this.source = source;
    this.videoOnly = videoOnly;
    this.loop = loop;
    this.startOffsetUs = startOffsetUs;
  }

  @SuppressWarnings("unchecked")
  public static VideoOptions fromMap(Map<?, ?> map, MediaSource source) {
    final Number startOffsetMs = (Number) map.get("startOffsetMS");
    final long startOffsetUs = startOffsetMs == null ? 0 : startOffsetMs.longValue() * 1000;
    return new VideoOptions(source, (Boolean) map.get("videoOnly"), (Boolean) map.get("loop"), startOffsetUs);
  }
}
