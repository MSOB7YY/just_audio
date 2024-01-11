package com.ryanheise.video_player;

import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.cache.CacheKeyFactory;

public class CustomCacheKeyProvider implements CacheKeyFactory {
  private final String cacheKey;

  CustomCacheKeyProvider(String cacheKey) {
    this.cacheKey = cacheKey;
  }

  @Override
  public String buildCacheKey(final DataSpec dataSpec) {
    if (this.cacheKey == null) {
      return dataSpec.key;
    } else {
      return cacheKey;
    }

  }
}
