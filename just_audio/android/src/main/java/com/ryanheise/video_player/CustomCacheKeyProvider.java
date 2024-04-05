package com.ryanheise.video_player;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheKeyFactory;


@UnstableApi public class CustomCacheKeyProvider implements CacheKeyFactory {
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
