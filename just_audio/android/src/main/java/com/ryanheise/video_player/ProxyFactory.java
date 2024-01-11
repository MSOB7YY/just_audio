
package com.ryanheise.video_player;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;

import com.danikula.videocache.HttpProxyCacheServer;
import com.danikula.videocache.file.DiskUsage;
import com.danikula.videocache.file.FileNameGenerator;
import com.danikula.videocache.headers.HeaderInjector;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ProxyFactory {

  public static HttpProxyCacheServer getProxy(Context context, @Nullable String cacheDirectory,
    Map<String, String> httpHeaders, @Nullable String cacheKey, @Nullable Long maxTotalCacheSize) {
    final File cacheDir = cacheDirectory != null ? new File(cacheDirectory) : context.getCacheDir();
    final Long totalCacheSize = maxTotalCacheSize != null ? maxTotalCacheSize : 1 * 1024 * 1024 * 1024;
    return new HttpProxyCacheServer.Builder(context).maxCacheSize(totalCacheSize).cacheDirectory(cacheDir)
      .headerInjector(new _UserAgentHeadersInjector(httpHeaders)).fileNameGenerator(new _MyFileNameGenerator(cacheKey))
      .build();
  }

}


class _UserAgentHeadersInjector implements HeaderInjector {
  private final Map<String, String> headers = new HashMap<>();

  _UserAgentHeadersInjector(Map<String, String> headers) {
    this.headers.clear();
    this.headers.putAll(headers);
  }

  @Override
  public Map<String, String> addHeaders(String url) {
    return headers;
  }
}


class _MyFileNameGenerator implements FileNameGenerator {

  private final String cacheKey;

  _MyFileNameGenerator(@Nullable String cacheKey) {
    this.cacheKey = cacheKey;
  }

  @Override
  public String generate(String url) {
    if (cacheKey == null) {
      try {
        byte[] bytesOfMessage;
        bytesOfMessage = url.getBytes("UTF-8");
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] md5Hash = md.digest(bytesOfMessage);
        return Arrays.toString(md5Hash);
      } catch (UnsupportedEncodingException ignore) {
      } catch (NoSuchAlgorithmException ignore) {
      }
      return String.valueOf(url.hashCode());
    } else {
      return cacheKey;
    }

  }
}
