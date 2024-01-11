package com.ryanheise.just_audio;

import androidx.annotation.Nullable;
import java.util.Map;

public class VideoOptions {
  String source;
  boolean enableCaching;
  String cacheKey;
  String cacheDirectory;
  Long maxSingleFileCacheSize;
  Long maxTotalCacheSize;

  Map<String, String> httpHeaders;

  String formatHint;

  public VideoOptions(String source, boolean enableCaching, @Nullable String cacheKey, @Nullable String cacheDirectory,
    @Nullable Long maxSingleFileCacheSize, @Nullable Long maxTotalCacheSize, Map<String, String> httpHeaders,
    String formatHint) {

    this.source = source;
    this.enableCaching = enableCaching;
    this.cacheKey = cacheKey;
    this.cacheDirectory = cacheDirectory;
    this.maxSingleFileCacheSize = maxSingleFileCacheSize;
    this.maxTotalCacheSize = maxTotalCacheSize;
    this.httpHeaders = httpHeaders;
    this.formatHint = formatHint;
  }

  @SuppressWarnings("unchecked")
  public static VideoOptions fromMap(Map<?, ?> map) {
    return new VideoOptions((String) map.get("source"), (boolean) map.get("enableCaching"),
      (String) map.get("cacheKey"), (String) map.get("cacheDirectory"),
      ((Number) map.get("maxSingleFileCacheSize")).longValue(), ((Number) map.get("maxTotalCacheSize")).longValue(),
      (Map<String, String>) map.get("httpHeaders"), (String) map.get("formatHint"));
  }
}
