package com.ryanheise.just_audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.util.Rational;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.Player.PositionInfo;
import com.google.android.exoplayer2.audio.*;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataOutput;
import com.google.android.exoplayer2.metadata.icy.IcyHeaders;
import com.google.android.exoplayer2.metadata.icy.IcyInfo;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.source.ShuffleOrder.DefaultShuffleOrder;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import io.flutter.Log;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry;

import com.ryanheise.video_player.*;
import com.danikula.videocache.HttpProxyCacheServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class AudioPlayer implements MethodCallHandler, Player.Listener, MetadataOutput {

  static final String TAG = "AudioPlayer";

  private static Random random = new Random();

  private final Context context;
  private final MethodChannel methodChannel;
  private final BetterEventChannel eventChannel;
  private final BetterEventChannel dataEventChannel;

  private ProcessingState processingState;
  private boolean handledVideoError;
  private long updatePosition;
  private long updateTime;
  private long bufferedPosition;
  private Long start;
  private Long end;
  private Long seekPos;
  private long initialPos;
  private Integer initialIndex;
  private Result prepareResult;
  private Result playResult;
  private Result seekResult;
  private Map<String, MediaSource> mediaSources = new HashMap<String, MediaSource>();
  private Map<String, MediaSource> videoSources = new HashMap<String, MediaSource>();
  private IcyInfo icyInfo;
  private IcyHeaders icyHeaders;
  private int errorCount;
  private AudioAttributes pendingAudioAttributes;
  private LoadControl loadControl;
  private boolean offloadSchedulingEnabled;
  private LivePlaybackSpeedControl livePlaybackSpeedControl;
  private List<Object> rawAudioEffects;
  private List<AudioEffect> audioEffects = new ArrayList<AudioEffect>();
  private Map<String, AudioEffect> audioEffectsMap = new HashMap<String, AudioEffect>();
  private int lastPlaylistLength = 0;
  private Map<String, Object> pendingPlaybackEvent;

  private final BetterEventChannel videoEventChannel;
  private final TextureRegistry.SurfaceTextureEntry textureEntry;
  private Surface surface;
  private VideoOptions videoOptions;
  private Boolean videoOnly;

  private DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory();

  private static final String USER_AGENT = "User-Agent";
  private static final String FORMAT_SS = "ss";
  private static final String FORMAT_DASH = "dash";
  private static final String FORMAT_HLS = "hls";
  private static final String FORMAT_OTHER = "other";

  private ExoPlayer player;
  private ExoPlayer loopingPlayer;
  private DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
  private Integer audioSessionId;
  private MediaSource mediaSource;
  private MediaSource audioSource;
  private MediaSource videoSource;
  private Integer currentIndex;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable bufferWatcher = new Runnable() {
    @Override
    public void run() {
      if (player == null) {
        return;
      }

      long newBufferedPosition = player.getBufferedPosition();
      if (newBufferedPosition != bufferedPosition) {
        // This method updates bufferedPosition.
        broadcastImmediatePlaybackEvent();
      }
      switch (player.getPlaybackState()) {
        case Player.STATE_BUFFERING:
          handler.postDelayed(this, 200);
          break;
        case Player.STATE_READY:
          if (player.getPlayWhenReady()) {
            handler.postDelayed(this, 500);
          } else {
            handler.postDelayed(this, 1000);
          }
          break;
        default:
          // Stop watching buffer
      }
    }
  };

  public AudioPlayer(final Context applicationContext, final BinaryMessenger messenger, final String id,
      Map<?, ?> audioLoadConfiguration, List<Object> rawAudioEffects, Boolean offloadSchedulingEnabled,
      TextureRegistry.SurfaceTextureEntry textureEntry) {
    this.context = applicationContext;
    this.rawAudioEffects = rawAudioEffects;
    this.offloadSchedulingEnabled = offloadSchedulingEnabled != null ? offloadSchedulingEnabled : false;
    this.textureEntry = textureEntry;
    surface = new Surface(textureEntry.surfaceTexture());
    methodChannel = new MethodChannel(messenger, "com.ryanheise.just_audio.methods." + id);
    methodChannel.setMethodCallHandler(this);
    eventChannel = new BetterEventChannel(messenger, "com.ryanheise.just_audio.events." + id);
    dataEventChannel = new BetterEventChannel(messenger, "com.ryanheise.just_audio.data." + id);
    videoEventChannel = new BetterEventChannel(messenger, "com.ryanheise.just_audio.video." + id);
    processingState = ProcessingState.none;
    extractorsFactory.setConstantBitrateSeekingEnabled(true);
    if (audioLoadConfiguration != null) {
      Map<?, ?> loadControlMap = (Map<?, ?>) audioLoadConfiguration.get("androidLoadControl");
      if (loadControlMap != null) {
        DefaultLoadControl.Builder builder = new DefaultLoadControl.Builder()
            .setBufferDurationsMs((int) ((getLong(loadControlMap.get("minBufferDuration"))) / 1000),
                (int) ((getLong(loadControlMap.get("maxBufferDuration"))) / 1000),
                (int) ((getLong(loadControlMap.get("bufferForPlaybackDuration"))) / 1000),
                (int) ((getLong(loadControlMap.get("bufferForPlaybackAfterRebufferDuration"))) / 1000))
            .setPrioritizeTimeOverSizeThresholds((Boolean) loadControlMap.get("prioritizeTimeOverSizeThresholds"))
            .setBackBuffer((int) ((getLong(loadControlMap.get("backBufferDuration"))) / 1000), false);
        if (loadControlMap.get("targetBufferBytes") != null) {
          builder.setTargetBufferBytes((Integer) loadControlMap.get("targetBufferBytes"));
        }
        loadControl = builder.build();
      }
      Map<?, ?> livePlaybackSpeedControlMap = (Map<?, ?>) audioLoadConfiguration.get("androidLivePlaybackSpeedControl");
      if (livePlaybackSpeedControlMap != null) {
        DefaultLivePlaybackSpeedControl.Builder builder = new DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(
                (float) ((double) ((Double) livePlaybackSpeedControlMap.get("fallbackMinPlaybackSpeed"))))
            .setFallbackMaxPlaybackSpeed(
                (float) ((double) ((Double) livePlaybackSpeedControlMap.get("fallbackMaxPlaybackSpeed"))))
            .setMinUpdateIntervalMs(((getLong(livePlaybackSpeedControlMap.get("minUpdateInterval"))) / 1000))
            .setProportionalControlFactor(
                (float) ((double) ((Double) livePlaybackSpeedControlMap.get("proportionalControlFactor"))))
            .setMaxLiveOffsetErrorMsForUnitSpeed(
                ((getLong(livePlaybackSpeedControlMap.get("maxLiveOffsetErrorForUnitSpeed"))) / 1000))
            .setTargetLiveOffsetIncrementOnRebufferMs(
                ((getLong(livePlaybackSpeedControlMap.get("targetLiveOffsetIncrementOnRebuffer"))) / 1000))
            .setMinPossibleLiveOffsetSmoothingFactor(
                (float) ((double) ((Double) livePlaybackSpeedControlMap.get("minPossibleLiveOffsetSmoothingFactor"))));
        livePlaybackSpeedControl = builder.build();
      }
    }
  }

  private void startWatchingBuffer() {
    handler.removeCallbacks(bufferWatcher);
    handler.post(bufferWatcher);
  }

  private void setAudioSessionId(int audioSessionId) {
    if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
      this.audioSessionId = null;
    } else {
      this.audioSessionId = audioSessionId;
    }
    clearAudioEffects();
    if (this.audioSessionId != null) {
      for (Object rawAudioEffect : rawAudioEffects) {
        Map<?, ?> json = (Map<?, ?>) rawAudioEffect;
        AudioEffect audioEffect = decodeAudioEffect(rawAudioEffect, this.audioSessionId);
        if ((Boolean) json.get("enabled")) {
          audioEffect.setEnabled(true);
        }
        audioEffects.add(audioEffect);
        audioEffectsMap.put((String) json.get("type"), audioEffect);
      }
    }
    enqueuePlaybackEvent();
  }

  @Override
  public void onAudioSessionIdChanged(int audioSessionId) {
    setAudioSessionId(audioSessionId);
    broadcastPendingPlaybackEvent();
  }

  @Override
  public void onMetadata(Metadata metadata) {
    for (int i = 0; i < metadata.length(); i++) {
      final Metadata.Entry entry = metadata.get(i);
      if (entry instanceof IcyInfo) {
        icyInfo = (IcyInfo) entry;
        broadcastImmediatePlaybackEvent();
      }
    }
  }

  @Override
  public void onTracksChanged(Tracks tracks) {
    for (int i = 0; i < tracks.getGroups().size(); i++) {
      TrackGroup trackGroup = tracks.getGroups().get(i).getMediaTrackGroup();

      for (int j = 0; j < trackGroup.length; j++) {
        Metadata metadata = trackGroup.getFormat(j).metadata;

        if (metadata != null) {
          for (int k = 0; k < metadata.length(); k++) {
            final Metadata.Entry entry = metadata.get(k);
            if (entry instanceof IcyHeaders) {
              icyHeaders = (IcyHeaders) entry;
              broadcastImmediatePlaybackEvent();
            }
          }
        }
      }
    }
  }

  private boolean updatePositionIfChanged() {
    if (getCurrentPosition() == updatePosition)
      return false;
    updatePosition = getCurrentPosition();
    updateTime = System.currentTimeMillis();
    return true;
  }

  private void updatePosition() {
    updatePosition = getCurrentPosition();
    updateTime = System.currentTimeMillis();
  }

  @Override
  public void onPositionDiscontinuity(PositionInfo oldPosition, PositionInfo newPosition, int reason) {
    updatePosition();
    switch (reason) {
      case Player.DISCONTINUITY_REASON_AUTO_TRANSITION:
      case Player.DISCONTINUITY_REASON_SEEK:
        updateCurrentIndex();
        break;
    }
    broadcastImmediatePlaybackEvent();
  }

  @Override
  public void onTimelineChanged(Timeline timeline, int reason) {
    if (initialPos != C.TIME_UNSET || initialIndex != null) {
      int windowIndex = initialIndex != null ? initialIndex : 0;
      player.seekTo(windowIndex, initialPos);
      initialIndex = null;
      initialPos = C.TIME_UNSET;
    }
    if (updateCurrentIndex()) {
      broadcastImmediatePlaybackEvent();
    }
    if (player.getPlaybackState() == Player.STATE_ENDED) {
      try {
        if (player.getPlayWhenReady()) {
          if (lastPlaylistLength == 0 && player.getMediaItemCount() > 0) {
            player.seekTo(0, 0L);
          } else if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem();
          }
        } else {
          if (player.getCurrentMediaItemIndex() < player.getMediaItemCount()) {
            player.seekTo(player.getCurrentMediaItemIndex(), 0L);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    lastPlaylistLength = player.getMediaItemCount();
  }

  private boolean updateCurrentIndex() {
    Integer newIndex = player.getCurrentMediaItemIndex();
    // newIndex is never null.
    // currentIndex is sometimes null.
    if (!newIndex.equals(currentIndex)) {
      currentIndex = newIndex;
      return true;
    }
    return false;
  }

  @Override
  public void onPlaybackStateChanged(int playbackState) {
    switch (playbackState) {
      case Player.STATE_READY:
        if (player.getPlayWhenReady())
          updatePosition();
        processingState = ProcessingState.ready;
        broadcastImmediatePlaybackEvent();
        sendVideoInfo();
        ppLoopingPlayer(true);
        if (prepareResult != null) {
          Map<String, Object> response = new HashMap<>();
          response.put("duration", getDuration() == C.TIME_UNSET ? null : (1000 * getDuration()));
          prepareResult.success(response);
          prepareResult = null;
          if (pendingAudioAttributes != null) {
            player.setAudioAttributes(pendingAudioAttributes, false);
            pendingAudioAttributes = null;
          }
        }
        if (seekResult != null) {
          completeSeek();
        }
        break;
      case Player.STATE_BUFFERING:
        ppLoopingPlayer(false);
        updatePositionIfChanged();
        if (processingState != ProcessingState.buffering && processingState != ProcessingState.loading) {
          processingState = ProcessingState.buffering;
          broadcastImmediatePlaybackEvent();
        }
        startWatchingBuffer();
        break;
      case Player.STATE_ENDED:
        ppLoopingPlayer(false);
        if (processingState != ProcessingState.completed) {
          updatePosition();
          processingState = ProcessingState.completed;
          broadcastImmediatePlaybackEvent();
        }
        if (prepareResult != null) {
          Map<String, Object> response = new HashMap<>();
          prepareResult.success(response);
          prepareResult = null;
          if (pendingAudioAttributes != null) {
            player.setAudioAttributes(pendingAudioAttributes, false);
            pendingAudioAttributes = null;
          }
        }
        if (playResult != null) {
          playResult.success(new HashMap<String, Object>());
          playResult = null;
        }
        break;
    }
  }

  @Override
  public void onPlayerError(PlaybackException error) {
    if (error instanceof ExoPlaybackException) {
      final ExoPlaybackException exoError = (ExoPlaybackException) error;
      if (handledVideoError == false && this.mediaSource instanceof MergingMediaSource) {
        handledVideoError = true;
        this.videoSource = null;
        this.videoOptions = null;
        this.mediaSource = this.audioSource;
        long pos = player.getCurrentPosition();
        if (pos < 0)
          pos = 0;
        player.setMediaSource(this.mediaSource);
        player.seekTo(pos);
        player.prepare();
        return;
      }
      switch (exoError.type) {
        case ExoPlaybackException.TYPE_SOURCE:
          Log.e(TAG, "TYPE_SOURCE: " + exoError.getSourceException().getMessage());
          break;

        case ExoPlaybackException.TYPE_RENDERER:
          Log.e(TAG, "TYPE_RENDERER: " + exoError.getRendererException().getMessage());
          break;

        case ExoPlaybackException.TYPE_UNEXPECTED:
          Log.e(TAG, "TYPE_UNEXPECTED: " + exoError.getUnexpectedException().getMessage());
          break;

        default:
          Log.e(TAG, "default ExoPlaybackException: " + exoError.getUnexpectedException().getMessage());
      }
      // TODO: send both errorCode and type
      sendError(String.valueOf(exoError.type), exoError.getMessage());
    } else {
      Log.e(TAG, "default PlaybackException: " + error.getMessage());
      sendError(String.valueOf(error.errorCode), error.getMessage());
    }
    errorCount++;
    if (player.hasNextMediaItem() && currentIndex != null && errorCount <= 5) {
      int nextIndex = currentIndex + 1;
      Timeline timeline = player.getCurrentTimeline();
      // This condition is due to: https://github.com/ryanheise/just_audio/pull/310
      if (nextIndex < timeline.getWindowCount()) {
        // TODO: pass in initial position here.
        player.setMediaSource(mediaSource);
        player.prepare();
        player.seekTo(nextIndex, 0);
      }
    }
  }

  private void completeSeek() {
    seekPos = null;
    seekResult.success(new HashMap<String, Object>());
    seekResult = null;
  }

  @Override
  public void onMethodCall(final MethodCall call, final Result result) {
    ensurePlayerInitialized();

    try {
      switch (call.method) {
        case "load":
          final Long initialPosition = getLong(call.argument("initialPosition"));
          final Integer initialIndex = call.argument("initialIndex");
          final Map<?, ?> videoOptionsMap = call.argument("videoOptions");
          final VideoOptions videoOptions = videoOptionsMap == null ? null : VideoOptions.fromMap(videoOptionsMap);
          load(getAudioSource(call.argument("audioSource")), getVideoSource(videoOptions), call.argument("videoOnly"),
              initialPosition == null ? C.TIME_UNSET : initialPosition / 1000, initialIndex, videoOptions,
              call.argument("keepOldVideoSource"), result);
          break;
        case "setVideo":
          setVideoOptions(call.argument("video"));
          result.success(new HashMap<String, Object>());
          break;
        case "play":
          play(result);
          break;
        case "pause":
          pause();
          result.success(new HashMap<String, Object>());
          break;
        case "setVolume":
          setVolume((float) ((double) ((Double) call.argument("volume"))));
          result.success(new HashMap<String, Object>());
          break;
        case "setSpeed":
          setSpeed((float) ((double) ((Double) call.argument("speed"))));
          result.success(new HashMap<String, Object>());
          break;
        case "setPitch":
          setPitch((float) ((double) ((Double) call.argument("pitch"))));
          result.success(new HashMap<String, Object>());
          break;
        case "setSkipSilence":
          setSkipSilenceEnabled((Boolean) call.argument("enabled"));
          result.success(new HashMap<String, Object>());
          break;
        case "setLoopMode":
          setLoopMode((Integer) call.argument("loopMode"));
          result.success(new HashMap<String, Object>());
          break;
        case "setShuffleMode":
          setShuffleModeEnabled((Integer) call.argument("shuffleMode") == 1);
          result.success(new HashMap<String, Object>());
          break;
        case "setShuffleOrder":
          setShuffleOrder(call.argument("audioSource"));
          result.success(new HashMap<String, Object>());
          break;
        case "setAutomaticallyWaitsToMinimizeStalling":
          result.success(new HashMap<String, Object>());
          break;
        case "setCanUseNetworkResourcesForLiveStreamingWhilePaused":
          result.success(new HashMap<String, Object>());
          break;
        case "setPreferredPeakBitRate":
          result.success(new HashMap<String, Object>());
          break;
        case "seek":
          Long position = getLong(call.argument("position"));
          Integer index = call.argument("index");
          seek(position == null ? C.TIME_UNSET : position / 1000, index, result);
          break;
        case "concatenatingInsertAll":
          concatenating(call.argument("id")).addMediaSources(call.argument("index"),
              getAudioSources(call.argument("children")), handler, () -> result.success(new HashMap<String, Object>()));
          concatenating(call.argument("id")).setShuffleOrder(decodeShuffleOrder(call.argument("shuffleOrder")));
          break;
        case "concatenatingRemoveRange":
          concatenating(call.argument("id")).removeMediaSourceRange(call.argument("startIndex"),
              call.argument("endIndex"), handler, () -> result.success(new HashMap<String, Object>()));
          concatenating(call.argument("id")).setShuffleOrder(decodeShuffleOrder(call.argument("shuffleOrder")));
          break;
        case "concatenatingMove":
          concatenating(call.argument("id")).moveMediaSource(call.argument("currentIndex"), call.argument("newIndex"),
              handler, () -> result.success(new HashMap<String, Object>()));
          concatenating(call.argument("id")).setShuffleOrder(decodeShuffleOrder(call.argument("shuffleOrder")));
          break;
        case "setAndroidAudioAttributes":
          setAudioAttributes(call.argument("contentType"), call.argument("flags"), call.argument("usage"));
          result.success(new HashMap<String, Object>());
          break;
        case "audioEffectSetEnabled":
          audioEffectSetEnabled(call.argument("type"), call.argument("enabled"));
          result.success(new HashMap<String, Object>());
          break;
        case "androidLoudnessEnhancerSetTargetGain":
          loudnessEnhancerSetTargetGain(call.argument("targetGain"));
          result.success(new HashMap<String, Object>());
          break;
        case "androidEqualizerGetParameters":
          result.success(equalizerAudioEffectGetParameters());
          break;
        case "androidEqualizerBandSetGain":
          equalizerBandSetGain(call.argument("bandIndex"), call.argument("gain"));
          result.success(new HashMap<String, Object>());
          break;
        case "getCurrentPreset":
          result.success(getCurrentPreset());
          break;
        case "getEqualizerPresets":
          result.success(getPresets());
          break;
        case "setEqualizerPreset":
          result.success(setPreset(call.argument("index")));
          break;
        default:
          result.notImplemented();
          break;
      }
    } catch (IllegalStateException e) {
      e.printStackTrace();
      result.error("Illegal state: " + e.getMessage(), null, null);
    } catch (Exception e) {
      e.printStackTrace();
      result.error("Error: " + e, null, null);
    } finally {
      broadcastPendingPlaybackEvent();
    }
  }

  private ShuffleOrder decodeShuffleOrder(List<Integer> indexList) {
    int[] shuffleIndices = new int[indexList.size()];
    for (int i = 0; i < shuffleIndices.length; i++) {
      shuffleIndices[i] = indexList.get(i);
    }
    return new DefaultShuffleOrder(shuffleIndices, random.nextLong());
  }

  private static int[] shuffle(int length, Integer firstIndex) {
    final int[] shuffleOrder = new int[length];
    for (int i = 0; i < length; i++) {
      final int j = random.nextInt(i + 1);
      shuffleOrder[i] = shuffleOrder[j];
      shuffleOrder[j] = i;
    }
    if (firstIndex != null) {
      for (int i = 1; i < length; i++) {
        if (shuffleOrder[i] == firstIndex) {
          final int v = shuffleOrder[0];
          shuffleOrder[0] = shuffleOrder[i];
          shuffleOrder[i] = v;
          break;
        }
      }
    }
    return shuffleOrder;
  }

  // Create a shuffle order optionally fixing the first index.
  private ShuffleOrder createShuffleOrder(int length, Integer firstIndex) {
    int[] shuffleIndices = shuffle(length, firstIndex);
    return new DefaultShuffleOrder(shuffleIndices, random.nextLong());
  }

  private ConcatenatingMediaSource concatenating(final Object index) {
    return (ConcatenatingMediaSource) mediaSources.get((String) index);
  }

  private void setShuffleOrder(final Object json) {
    Map<?, ?> map = (Map<?, ?>) json;
    String id = mapGet(map, "id");
    MediaSource mediaSource = mediaSources.get(id);
    if (mediaSource == null)
      return;
    switch ((String) mapGet(map, "type")) {
      case "concatenating":
        ConcatenatingMediaSource concatenatingMediaSource = (ConcatenatingMediaSource) mediaSource;
        concatenatingMediaSource.setShuffleOrder(decodeShuffleOrder(mapGet(map, "shuffleOrder")));
        List<Object> children = mapGet(map, "children");
        for (Object child : children) {
          setShuffleOrder(child);
        }
        break;
      case "looping":
        setShuffleOrder(mapGet(map, "child"));
        break;
    }
  }

  private MediaSource getVideoSource(final VideoOptions options) {
    if (options == null)
      return null;
    final String id = options.source;
    MediaSource videoSource = videoSources.get(id);
    if (videoSource == null) {
      videoSource = decodeVideoSource(options);
      videoSources.put(id, videoSource);
    }
    return videoSource;
  }

  private MediaSource decodeVideoSource(VideoOptions options) {

    final Map<String, String> httpHeaders = options.httpHeaders;
    buildHttpDataSourceFactory(httpHeaders);
    DataSource.Factory dataSourceFactory;

    Uri uri = Uri.parse(options.source);

    final boolean shouldUseProxyCaching = options.cacheDirectory != null;

    if (options.enableCaching && isHTTP(uri)) {
      if (shouldUseProxyCaching) {
        final HttpProxyCacheServer proxy = ProxyFactory.getProxy(context, options.cacheDirectory, httpHeaders,
            options.cacheKey, options.maxTotalCacheSize);
        final String proxyUrl = proxy.getProxyUrl(options.source);
        uri = Uri.parse(proxyUrl);
        dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
      } else {
        CacheDataSourceFactory cacheDataSourceFactory = new CacheDataSourceFactory(context, options.maxTotalCacheSize,
            options.maxSingleFileCacheSize, options.cacheKey);
        if (!httpHeaders.isEmpty()) {
          cacheDataSourceFactory.setHeaders(httpHeaders);
        }
        dataSourceFactory = cacheDataSourceFactory;
      }

    } else {
      dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
    }

    int type;
    if (options.formatHint == null) {
      type = Util.inferContentType(uri);
    } else {
      switch (options.formatHint) {
        case FORMAT_SS:
          type = C.CONTENT_TYPE_SS;
          break;
        case FORMAT_DASH:
          type = C.CONTENT_TYPE_DASH;
          break;
        case FORMAT_HLS:
          type = C.CONTENT_TYPE_HLS;
          break;
        case FORMAT_OTHER:
          type = C.CONTENT_TYPE_OTHER;
          break;
        default:
          type = -1;
          break;
      }
    }
    switch (type) {
      case C.CONTENT_TYPE_SS:
        return new SsMediaSource.Factory(new DefaultSsChunkSource.Factory(dataSourceFactory), dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri));
      case C.CONTENT_TYPE_DASH:
        return new DashMediaSource.Factory(new DefaultDashChunkSource.Factory(dataSourceFactory), dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri));
      case C.CONTENT_TYPE_HLS:
        return new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
      case C.CONTENT_TYPE_OTHER:
        return new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
      default: {
        throw new IllegalStateException("Unsupported type: " + type);
      }
    }
  }

  public void buildHttpDataSourceFactory(Map<String, String> httpHeaders) {
    final boolean httpHeadersNotEmpty = !httpHeaders.isEmpty();
    final String userAgent = httpHeadersNotEmpty && httpHeaders.containsKey(USER_AGENT) ? httpHeaders.get(USER_AGENT)
        : "ExoPlayer";

    httpDataSourceFactory.setUserAgent(userAgent).setAllowCrossProtocolRedirects(true);

    if (httpHeadersNotEmpty) {
      httpDataSourceFactory.setDefaultRequestProperties(httpHeaders);
    }
  }

  private static boolean isHTTP(Uri uri) {
    if (uri == null)
      return false;
    final String scheme = uri.getScheme();
    if (scheme == null)
      return false;
    return scheme.equals("http") || scheme.equals("https");
  }

  private MediaSource getAudioSource(final Object json) {
    Map<?, ?> map = (Map<?, ?>) json;
    String id = (String) map.get("id");
    MediaSource mediaSource = mediaSources.get(id);
    if (mediaSource == null) {
      mediaSource = decodeAudioSource(map);
      mediaSources.put(id, mediaSource);
    }
    return mediaSource;
  }

  private MediaSource decodeAudioSource(final Object json) {
    Map<?, ?> map = (Map<?, ?>) json;
    String id = (String) map.get("id");
    switch ((String) map.get("type")) {
      case "progressive":
        return new ProgressiveMediaSource.Factory(buildDataSourceFactory(), extractorsFactory)
            .createMediaSource(new MediaItem.Builder().setUri(Uri.parse((String) map.get("uri"))).setTag(id).build());
      case "dash":
        return new DashMediaSource.Factory(buildDataSourceFactory()).createMediaSource(new MediaItem.Builder()
            .setUri(Uri.parse((String) map.get("uri"))).setMimeType(MimeTypes.APPLICATION_MPD).setTag(id).build());
      case "hls":
        return new HlsMediaSource.Factory(buildDataSourceFactory()).createMediaSource(new MediaItem.Builder()
            .setUri(Uri.parse((String) map.get("uri"))).setMimeType(MimeTypes.APPLICATION_M3U8).build());
      case "silence":
        return new SilenceMediaSource.Factory().setDurationUs(getLong(map.get("duration"))).setTag(id)
            .createMediaSource();
      case "concatenating":
        MediaSource[] mediaSources = getAudioSourcesArray(map.get("children"));
        return new ConcatenatingMediaSource(false, // isAtomic
            (Boolean) map.get("useLazyPreparation"), decodeShuffleOrder(mapGet(map, "shuffleOrder")), mediaSources);
      case "clipping":
        Long start = getLong(map.get("start"));
        Long end = getLong(map.get("end"));
        return new ClippingMediaSource(getAudioSource(map.get("child")), start != null ? start : 0,
            end != null ? end : C.TIME_END_OF_SOURCE);
      case "looping":
        Integer count = (Integer) map.get("count");
        MediaSource looperChild = getAudioSource(map.get("child"));
        MediaSource[] looperChildren = new MediaSource[count];
        for (int i = 0; i < looperChildren.length; i++) {
          looperChildren[i] = looperChild;
        }
        return new ConcatenatingMediaSource(looperChildren);
      default:
        throw new IllegalArgumentException("Unknown AudioSource type: " + map.get("type"));
    }
  }

  private MediaSource[] getAudioSourcesArray(final Object json) {
    List<MediaSource> mediaSources = getAudioSources(json);
    MediaSource[] mediaSourcesArray = new MediaSource[mediaSources.size()];
    mediaSources.toArray(mediaSourcesArray);
    return mediaSourcesArray;
  }

  private List<MediaSource> getAudioSources(final Object json) {
    if (!(json instanceof List))
      throw new RuntimeException("List expected: " + json);
    List<?> audioSources = (List<?>) json;
    List<MediaSource> mediaSources = new ArrayList<MediaSource>();
    for (int i = 0; i < audioSources.size(); i++) {
      mediaSources.add(getAudioSource(audioSources.get(i)));
    }
    return mediaSources;
  }

  private AudioEffect decodeAudioEffect(final Object json, int audioSessionId) {
    Map<?, ?> map = (Map<?, ?>) json;
    String type = (String) map.get("type");
    switch (type) {
      case "AndroidLoudnessEnhancer":
        if (Build.VERSION.SDK_INT < 19)
          throw new RuntimeException("AndroidLoudnessEnhancer requires minSdkVersion >= 19");
        int targetGain = (int) Math.round((((Double) map.get("targetGain")) * 1000.0));
        LoudnessEnhancer loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
        loudnessEnhancer.setTargetGain(targetGain);
        return loudnessEnhancer;
      case "AndroidEqualizer":
        Equalizer equalizer = new Equalizer(0, audioSessionId);
        return equalizer;
      default:
        throw new IllegalArgumentException("Unknown AudioEffect type: " + map.get("type"));
    }
  }

  private void clearAudioEffects() {
    for (Iterator<AudioEffect> it = audioEffects.iterator(); it.hasNext();) {
      AudioEffect audioEffect = it.next();
      audioEffect.release();
      it.remove();
    }
    audioEffectsMap.clear();
  }

  private DataSource.Factory buildDataSourceFactory() {
    String userAgent = Util.getUserAgent(context, "just_audio");
    DataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory().setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(true);
    return new DefaultDataSource.Factory(context, httpDataSourceFactory);
  }

  private void load(final MediaSource audioSource, final MediaSource videoSource, final Boolean videoOnly,
      final long initialPosition,
      final Integer initialIndex, VideoOptions videoOptions, Boolean keepOldVideoSource, final Result result) {
    this.initialPos = initialPosition;
    this.initialIndex = initialIndex;
    this.videoOptions = videoOptions;
    this.videoOnly = videoOnly == null ? false : videoOnly;
    currentIndex = initialIndex != null ? initialIndex : 0;
    switch (processingState) {
      case none:
        break;
      case loading:
        abortExistingConnection();
        player.stop();
        break;
      default:
        player.stop();
        break;
    }
    errorCount = 0;
    prepareResult = result;
    updatePosition();
    processingState = ProcessingState.loading;
    enqueuePlaybackEvent();
    this.audioSource = audioSource;

    // always update video source, unless specified.
    if (keepOldVideoSource == null || !keepOldVideoSource) {
      this.videoOptions = videoOptions;
      this.videoSource = videoSource;
      this.handledVideoError = false;
      sendDisposeVideo();
    }

    // TODO: pass in initial position here.
    if (this.videoSource != null) {
      if (this.videoOnly) {
        this.mediaSource = videoSource;
      } else {
        this.mediaSource = new MergingMediaSource(audioSource, videoSource);
      }
    } else {
      this.mediaSource = audioSource;
    }

    player.setMediaSource(this.mediaSource);
    // player.setVideoSurface(surface);
    player.prepare();
  }

  private void setVideoOptions(final Map<?, ?> map) {
    sendDisposeVideo();

    final VideoOptions videoOptions = map == null ? null : VideoOptions.fromMap(map);
    final MediaSource videoSource = getVideoSource(videoOptions);
    this.videoOptions = videoOptions;
    this.videoSource = videoSource;
    this.handledVideoError = false;

    final Boolean silentVideoDisposal = false;

    if (videoOptions != null && videoOptions.loop) {
      updateLoopingPlayer();
    } else {
      disposeLoopingPlayer();
      MediaSource finalSource = null;
      if (videoSource != null) {
        finalSource = new MergingMediaSource(this.audioSource, videoSource);
      } else {
        // null video, remove if was set before
        if (this.mediaSource instanceof MergingMediaSource) {
          if (silentVideoDisposal) {
            sendDisposeVideo();
          } else {
            finalSource = this.audioSource;
          }
        }
        // else {} // video is null && current source is already audio only, do nothing.
      }

      if (finalSource != null) {
        this.mediaSource = finalSource;
        long pos = player.getCurrentPosition();
        if (pos < 0)
          pos = 0;
        player.setMediaSource(this.mediaSource);
        player.seekTo(pos);
        // player.setVideoSurface(surface);
        player.prepare();
      }
    }

  }

  private void updateLoopingPlayer() {
    if (this.videoSource != null) {
      if (loopingPlayer == null) {
        // -- ensure initialized
        ExoPlayer.Builder builder = new ExoPlayer.Builder(context);
        if (loadControl != null) {
          builder.setLoadControl(loadControl);
        }
        loopingPlayer = builder.build();
        loopingPlayer.experimentalSetOffloadSchedulingEnabled(offloadSchedulingEnabled);
        loopingPlayer.setVideoSurface(surface);
      }
      loopingPlayer.setMediaSource(this.videoSource);
      loopingPlayer.prepare();
      loopingPlayer.setVolume(0);
      loopingPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
      loopingPlayer.addListener(new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
          if (state == Player.STATE_READY) {
            sendVideoInfo();
          }
        }
      });
    } else {
      disposeLoopingPlayer();
    }
  }

  private void disposeLoopingPlayer() {
    if (loopingPlayer != null) {
      loopingPlayer.clearVideoSurface();
      loopingPlayer.release();
      loopingPlayer = null;
    }
  }

  private void sendDisposeVideo() {
    // player.clearVideoSurface();
    final Map<String, Integer> event = new HashMap<>();
    event.put("textureId", -1);
    videoEventChannel.success(event);
  }

  private void sendVideoInfo() {
    final Map<String, Object> videoInfoMap = new HashMap<String, Object>();
    final Format videoInfo = (loopingPlayer != null ? loopingPlayer : player).getVideoFormat();
    videoInfoMap.put("textureId", videoSource == null || videoInfo == null ? -1 : textureEntry.id());
    if (videoInfo != null) {
      videoInfoMap.put("id", videoInfo.id);
      videoInfoMap.put("width", videoInfo.width);
      videoInfoMap.put("height", videoInfo.height);
      videoInfoMap.put("frameRate", videoInfo.frameRate);
      videoInfoMap.put("bitrate", videoInfo.bitrate);
      videoInfoMap.put("sampleRate", videoInfo.sampleRate);
      videoInfoMap.put("encoderDelay", videoInfo.encoderDelay);
      videoInfoMap.put("rotationDegrees", videoInfo.rotationDegrees);
      videoInfoMap.put("containerMimeType", videoInfo.containerMimeType);
      videoInfoMap.put("label", videoInfo.label);
      videoInfoMap.put("language", videoInfo.language);
    }
    videoEventChannel.success(videoInfoMap);
  }

  private void ensurePlayerInitialized() {
    if (player == null) {
      ExoPlayer.Builder builder = new ExoPlayer.Builder(context);
      if (loadControl != null) {
        builder.setLoadControl(loadControl);
      }
      if (livePlaybackSpeedControl != null) {
        builder.setLivePlaybackSpeedControl(livePlaybackSpeedControl);
      }

      final long minSilenceDur = 2_000_000; // 2 seconds
      final long paddingSilenceDur = 200_000; // 200 ms
      final short silenceThresholdPCM = 512;

      RenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
        @Override
        protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
            boolean enableAudioTrackPlaybackParams, boolean enableOffload) {
          return new DefaultAudioSink.Builder()
              .setAudioProcessorChain(new DefaultAudioSink.DefaultAudioProcessorChain(new AudioProcessor[0], // silence
                  // and
                  // sonic
                  // processor
                  // only
                  new SilenceSkippingAudioProcessor(minSilenceDur, paddingSilenceDur, silenceThresholdPCM),
                  new SonicAudioProcessor()))
              .setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)

              .build();
        }
      }.setEnableAudioOffload(offloadSchedulingEnabled);

      builder.setRenderersFactory(renderersFactory);
      player = builder.build();
      player.experimentalSetOffloadSchedulingEnabled(offloadSchedulingEnabled);
      setAudioSessionId(player.getAudioSessionId());
      player.setVideoSurface(surface);
      player.addListener(this);
    }
  }

  private void setAudioAttributes(int contentType, int flags, int usage) {
    AudioAttributes.Builder builder = new AudioAttributes.Builder();
    builder.setContentType(contentType);
    builder.setFlags(flags);
    builder.setUsage(usage);
    // builder.setAllowedCapturePolicy((Integer)json.get("allowedCapturePolicy"));
    AudioAttributes audioAttributes = builder.build();
    if (processingState == ProcessingState.loading) {
      // audio attributes should be set either before or after loading to
      // avoid an ExoPlayer glitch.
      pendingAudioAttributes = audioAttributes;
    } else {
      player.setAudioAttributes(audioAttributes, false);
    }
  }

  private void audioEffectSetEnabled(String type, boolean enabled) {
    audioEffectsMap.get(type).setEnabled(enabled);
  }

  @SuppressLint("NewApi")
  private void loudnessEnhancerSetTargetGain(double targetGain) {
    int targetGainMillibels = (int) Math.round(targetGain * 1000.0);
    ((LoudnessEnhancer) audioEffectsMap.get("AndroidLoudnessEnhancer")).setTargetGain(targetGainMillibels);
  }

  private Map<String, Object> equalizerAudioEffectGetParameters() {
    Equalizer equalizer = (Equalizer) audioEffectsMap.get("AndroidEqualizer");
    ArrayList<Object> rawBands = new ArrayList<>();
    for (short i = 0; i < equalizer.getNumberOfBands(); i++) {
      rawBands.add(mapOf("index", i, "lowerFrequency", (double) equalizer.getBandFreqRange(i)[0] / 1000.0,
          "upperFrequency", (double) equalizer.getBandFreqRange(i)[1] / 1000.0, "centerFrequency",
          (double) equalizer.getCenterFreq(i) / 1000.0, "gain", equalizer.getBandLevel(i) / 1000.0));
    }
    return mapOf("parameters", mapOf("minDecibels", equalizer.getBandLevelRange()[0] / 1000.0, "maxDecibels",
        equalizer.getBandLevelRange()[1] / 1000.0, "bands", rawBands));
  }

  private void equalizerBandSetGain(int bandIndex, double gain) {
    ((Equalizer) audioEffectsMap.get("AndroidEqualizer")).setBandLevel((short) bandIndex,
        (short) (Math.round(gain * 1000.0)));
  }

  private Short getCurrentPreset() {
    try {
      final Equalizer equalizer = (Equalizer) audioEffectsMap.get("AndroidEqualizer");
      return equalizer.getCurrentPreset();
    } catch (Exception e) {
      return null;
    }
  }

  private List<String> getPresets() {
    final Equalizer equalizer = (Equalizer) audioEffectsMap.get("AndroidEqualizer");
    final List<String> presets = new ArrayList<>();

    final short numPresets = equalizer.getNumberOfPresets();
    for (short i = 0; i < numPresets; i++) {
      presets.add(equalizer.getPresetName(i));
    }
    return presets;
  }

  private Short setPreset(Integer presetIndex) {
    try {
      final Equalizer equalizer = (Equalizer) audioEffectsMap.get("AndroidEqualizer");
      equalizer.usePreset(presetIndex.shortValue());
      return equalizer.getCurrentPreset();
    } catch (Exception e) {
      return null;
    }
  }

  /// Creates an event based on the current state.
  private Map<String, Object> createPlaybackEvent() {
    final Map<String, Object> event = new HashMap<String, Object>();
    Long duration = getDuration() == C.TIME_UNSET ? null : (1000 * getDuration());
    bufferedPosition = player != null ? player.getBufferedPosition() : 0L;
    event.put("processingState", processingState.ordinal());
    event.put("updatePosition", 1000 * updatePosition);
    event.put("updateTime", updateTime);
    event.put("bufferedPosition", 1000 * Math.max(updatePosition, bufferedPosition));
    event.put("icyMetadata", collectIcyMetadata());
    event.put("duration", duration);
    event.put("currentIndex", currentIndex);
    event.put("androidAudioSessionId", audioSessionId);
    return event;
  }

  // Broadcast the pending playback event if it was set.
  private void broadcastPendingPlaybackEvent() {
    if (pendingPlaybackEvent != null) {
      eventChannel.success(pendingPlaybackEvent);
      pendingPlaybackEvent = null;
    }
  }

  // Set a pending playback event that should be broadcast at
  // a later time. If we're in a Flutter method call, it will
  // be broadcast just before that method call returns. If
  // we're in an asynchronous callback, it is up to the caller
  // to eventually broadcast that event via
  // broadcastPendingPlaybackEvent.
  //
  // If this is called multiple times before
  // broadcastPendingPlaybackEvent, only the last event is
  // broadcast.
  private void enqueuePlaybackEvent() {
    final Map<String, Object> event = new HashMap<String, Object>();
    pendingPlaybackEvent = createPlaybackEvent();
  }

  // Broadcasts a new event immediately.
  private void broadcastImmediatePlaybackEvent() {
    enqueuePlaybackEvent();
    broadcastPendingPlaybackEvent();
  }

  private Map<String, Object> collectIcyMetadata() {
    final Map<String, Object> icyData = new HashMap<>();
    if (icyInfo != null) {
      final Map<String, String> info = new HashMap<>();
      info.put("title", icyInfo.title);
      info.put("url", icyInfo.url);
      icyData.put("info", info);
    }
    if (icyHeaders != null) {
      final Map<String, Object> headers = new HashMap<>();
      headers.put("bitrate", icyHeaders.bitrate);
      headers.put("genre", icyHeaders.genre);
      headers.put("name", icyHeaders.name);
      headers.put("metadataInterval", icyHeaders.metadataInterval);
      headers.put("url", icyHeaders.url);
      headers.put("isPublic", icyHeaders.isPublic);
      icyData.put("headers", headers);
    }
    return icyData;
  }

  private long getCurrentPosition() {
    if (initialPos != C.TIME_UNSET) {
      return initialPos;
    } else if (processingState == ProcessingState.none || processingState == ProcessingState.loading) {
      long pos = player.getCurrentPosition();
      if (pos < 0)
        pos = 0;
      return pos;
    } else if (seekPos != null && seekPos != C.TIME_UNSET) {
      return seekPos;
    } else {
      return player.getCurrentPosition();
    }
  }

  private long getDuration() {
    if (processingState == ProcessingState.none || processingState == ProcessingState.loading) {
      return C.TIME_UNSET;
    } else {
      return player.getDuration();
    }
  }

  private void sendError(String errorCode, String errorMsg) {
    if (prepareResult != null) {
      prepareResult.error(errorCode, errorMsg, null);
      prepareResult = null;
    }

    eventChannel.error(errorCode, errorMsg, null);
  }

  private String getLowerCaseExtension(Uri uri) {
    // Until ExoPlayer provides automatic detection of media source types,
    // we
    // rely on the file extension. When this is absent, as a temporary
    // workaround we allow the app to supply a fake extension in the URL
    // fragment. e.g. https://somewhere.com/somestream?x=etc#.m3u8
    String fragment = uri.getFragment();
    String filename = fragment != null && fragment.contains(".") ? fragment : uri.getPath();
    return filename.replaceAll("^.*\\.", "").toLowerCase();
  }

  public void ppLoopingPlayer(Boolean playWhenReady) {
    if (loopingPlayer != null)
      loopingPlayer.setPlayWhenReady(playWhenReady);
  }

  public void play(Result result) {
    if (player.getPlayWhenReady()) {
      result.success(new HashMap<String, Object>());
      return;
    }
    if (playResult != null) {
      playResult.success(new HashMap<String, Object>());
    }
    playResult = result;
    player.setPlayWhenReady(true);
    ppLoopingPlayer(true);
    updatePosition();
    if (processingState == ProcessingState.completed && playResult != null) {
      playResult.success(new HashMap<String, Object>());
      playResult = null;
    }
  }

  public void pause() {
    if (!player.getPlayWhenReady())
      return;
    player.setPlayWhenReady(false);
    ppLoopingPlayer(false);
    updatePosition();
    if (playResult != null) {
      playResult.success(new HashMap<String, Object>());
      playResult = null;
    }
  }

  public void setVolume(final float volume) {
    player.setVolume(volume);
  }

  public void setSpeed(final float speed) {
    PlaybackParameters params = player.getPlaybackParameters();
    if (params.speed == speed)
      return;
    player.setPlaybackParameters(new PlaybackParameters(speed, params.pitch));
    if (player.getPlayWhenReady())
      updatePosition();
    enqueuePlaybackEvent();
  }

  public void setPitch(final float pitch) {
    PlaybackParameters params = player.getPlaybackParameters();
    if (params.pitch == pitch)
      return;
    player.setPlaybackParameters(new PlaybackParameters(params.speed, pitch));
    enqueuePlaybackEvent();
  }

  public void setSkipSilenceEnabled(final boolean enabled) {
    player.setSkipSilenceEnabled(enabled);
  }

  public void setLoopMode(final int mode) {
    player.setRepeatMode(mode);
  }

  public void setShuffleModeEnabled(final boolean enabled) {
    player.setShuffleModeEnabled(enabled);
  }

  public void seek(final long position, final Integer index, final Result result) {
    if (processingState == ProcessingState.none || processingState == ProcessingState.loading) {
      result.success(new HashMap<String, Object>());
      return;
    }
    abortSeek();
    seekPos = position;
    seekResult = result;
    try {
      int windowIndex = index != null ? index : player.getCurrentMediaItemIndex();
      player.seekTo(windowIndex, position);
    } catch (RuntimeException e) {
      seekResult = null;
      seekPos = null;
      throw e;
    }
  }

  public void dispose() {
    if (processingState == ProcessingState.loading) {
      abortExistingConnection();
    }
    if (playResult != null) {
      playResult.success(new HashMap<String, Object>());
      playResult = null;
    }
    mediaSource = null;
    mediaSources.clear();
    videoSources.clear();
    clearAudioEffects();
    if (player != null) {
      player.release();
      player = null;
      processingState = ProcessingState.none;
      broadcastImmediatePlaybackEvent();
    }
    eventChannel.endOfStream();
    dataEventChannel.endOfStream();

    // -- video
    // sendDisposeVideo();
    videoEventChannel.endOfStream();
    textureEntry.release();
    if (surface != null) {
      surface.release();
    }
  }

  private void abortSeek() {
    if (seekResult != null) {
      try {
        seekResult.success(new HashMap<String, Object>());
      } catch (RuntimeException e) {
        // Result already sent
      }
      seekResult = null;
      seekPos = null;
    }
  }

  private void abortExistingConnection() {
    sendError("abort", "Connection aborted");
  }

  // Dart can't distinguish between int sizes so
  // Flutter may send us a Long or an Integer
  // depending on the number of bits required to
  // represent it.
  public static Long getLong(Object o) {
    return (o == null || o instanceof Long) ? (Long) o : Long.valueOf(((Integer) o).intValue());
  }

  @SuppressWarnings("unchecked")
  static <T> T mapGet(Object o, String key) {
    if (o instanceof Map) {
      return (T) ((Map<?, ?>) o).get(key);
    } else {
      return null;
    }
  }

  static Map<String, Object> mapOf(Object... args) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      map.put((String) args[i], args[i + 1]);
    }
    return map;
  }

  enum ProcessingState {
    none, loading, buffering, ready, completed
  }

  public Boolean willPlayWhenReady() {
    return player.getPlayWhenReady();
  }

  public Boolean isPlaying() {
    return player.isPlaying();
  }

  public Boolean hasVideo() {
    return videoSource != null;
  }

  public Rational getVideoRational() {
    if (!hasVideo())
      return null;
    final Format info = (loopingPlayer != null ? loopingPlayer : player).getVideoFormat();
    return info == null ? new Rational(1, 1) : new Rational(info.width, info.height);
  }

}
