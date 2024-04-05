package com.ryanheise.video_player;

import android.content.Context;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;


import java.io.File;

@UnstableApi public class SimpleCacheSingleton {
  LeastRecentlyUsedCacheEvictor evictor;
  SimpleCache simpleCache;

  private static SimpleCacheSingleton instance;

  private SimpleCacheSingleton(Context context, long maxCacheSize) {
    evictor = new LeastRecentlyUsedCacheEvictor(maxCacheSize);
    simpleCache =
      new SimpleCache(new File(context.getCacheDir(), "media"), evictor, new StandaloneDatabaseProvider(context));
  }

  public synchronized static SimpleCacheSingleton getInstance(Context context, long maxCacheSize) {
    if (instance == null) {
      synchronized (SimpleCacheSingleton.class) {
        if (instance == null) instance = new SimpleCacheSingleton(context, maxCacheSize);
      }
    }
    return instance;
  }
}
