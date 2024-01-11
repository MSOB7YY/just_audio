package com.ryanheise.video_player;

import android.content.Context;
import androidx.annotation.Nullable;
import android.util.Log;

import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.*;

import java.util.Map;

public class CacheDataSourceFactory implements DataSource.Factory {
    private final Context context;
    private DefaultDataSource.Factory defaultDatasourceFactory;
    private final long maxFileSize, maxCacheSize;

    private DefaultHttpDataSource.Factory defaultHttpDataSourceFactory;

    private final String cacheKey;

    public CacheDataSourceFactory(Context context, @Nullable Long maxCacheSize, @Nullable Long maxFileSize, String cacheKey) {
        super();
        this.context = context;
        this.maxCacheSize = maxCacheSize != null ? maxCacheSize : 1 * 1024 * 1024 * 1024;
        this.maxFileSize = maxFileSize != null ? maxFileSize : 100 * 1024 * 1024;
        this.cacheKey = cacheKey;

        defaultHttpDataSourceFactory = new DefaultHttpDataSource.Factory();
        defaultHttpDataSourceFactory.setUserAgent("ExoPlayer");
        defaultHttpDataSourceFactory.setAllowCrossProtocolRedirects(true);
    }

    public void setHeaders(Map<String, String> httpHeaders) {
        defaultHttpDataSourceFactory.setDefaultRequestProperties(httpHeaders);
    }

    @Override
    public DataSource createDataSource() {
        final DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter.Builder(context).build();

        defaultDatasourceFactory = new DefaultDataSource.Factory(this.context, defaultHttpDataSourceFactory);
        defaultDatasourceFactory.setTransferListener(bandwidthMeter);

        final SimpleCache simpleCache = SimpleCacheSingleton.getInstance(context, maxCacheSize).simpleCache;
        final CacheKeyFactory cacheKeyProvider = new CustomCacheKeyProvider(this.cacheKey);
        return new CacheDataSource(simpleCache, defaultDatasourceFactory.createDataSource(), new FileDataSource(),
                new CacheDataSink(simpleCache, maxFileSize),
                CacheDataSource.FLAG_BLOCK_ON_CACHE | CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR, null, cacheKeyProvider);
    }

}
