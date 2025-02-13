package com.ryanheise.just_audio;

import android.content.Context;
import android.util.Rational;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@UnstableApi
public class MainMethodCallHandler implements MethodCallHandler {

  private final Context applicationContext;
  private final BinaryMessenger messenger;
  private final TextureRegistry textureRegistry;

  static private final Map<String, AudioPlayer> players = new HashMap<>();
  static private String latestId;

  static public AudioPlayer latestAudioPlayer() {
    return latestId == null ? null : players.get(latestId);
  }

  static public Boolean willPlayWhenReady() {
    final AudioPlayer pl = latestAudioPlayer();
    return pl == null ? false : pl.willPlayWhenReady();
  }

  static public Boolean isPlaying() {
    final AudioPlayer pl = latestAudioPlayer();
    return pl == null ? false : pl.isPlaying();
  }

  static public Boolean hasVideo() {
    final AudioPlayer pl = latestAudioPlayer();
    return pl == null ? false : pl.hasVideo();
  }

  static public Rational getVideoRational() {
    final AudioPlayer pl = latestAudioPlayer();
    return pl == null ? null : pl.getVideoRational();
  }

  public MainMethodCallHandler(Context applicationContext, BinaryMessenger messenger, TextureRegistry textureRegistry) {
    this.applicationContext = applicationContext;
    this.messenger = messenger;
    this.textureRegistry = textureRegistry;
  }

  @Override
  public void onMethodCall(MethodCall call, @NonNull Result result) {
    switch (call.method) {
      case "init": {
        final String id = call.argument("id");
        AudioPlayer oldplayer = players.get(id);
        if (oldplayer != null) {
          oldplayer.dispose();
          players.remove(id);
        }
        latestId = id;
        final List<Object> rawAudioEffects = call.argument("androidAudioEffects");
        final TextureRegistry.SurfaceProducer surface = textureRegistry.createSurfaceProducer();
        players.put(id, new AudioPlayer(applicationContext, messenger, id, call.argument("audioLoadConfiguration"),
            rawAudioEffects, textureRegistry));
        result.success(null);
        break;
      }
      case "freePlayer": {
        String id = call.argument("id");
        AudioPlayer player = players.get(id);
        if (player != null) {
          player.freeTemporarily();
        }
        result.success(new HashMap<String, Object>());
        break;
      }
      case "disposePlayer": {
        String id = call.argument("id");
        AudioPlayer player = players.get(id);
        if (player != null) {
          player.dispose();
          players.remove(id);
          if (latestId == id)
            latestId = null;
        }
        result.success(new HashMap<String, Object>());
        break;
      }
      case "disposeAllPlayers": {
        dispose();
        latestId = null;
        result.success(new HashMap<String, Object>());
        AudioPlayer.mediaSources.clear();
        AudioPlayer.videoSources.clear();
        players.clear();
        break;
      }
      default:
        result.notImplemented();
        break;
    }
  }

  void dispose() {
    for (AudioPlayer player : new ArrayList<AudioPlayer>(players.values())) {
      player.dispose();
    }
    players.clear();
  }
}
