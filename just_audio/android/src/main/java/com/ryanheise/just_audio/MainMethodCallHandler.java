package com.ryanheise.just_audio;

import android.content.Context;
import androidx.annotation.NonNull;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public class MainMethodCallHandler implements MethodCallHandler {

  private final Context applicationContext;
  private final BinaryMessenger messenger;
  private final TextureRegistry textureRegistry;

  private final Map<String, AudioPlayer> players = new HashMap<>();

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
        if (players.containsKey(id)) {
          result.error("Platform player " + id + " already exists", null, null);
          break;
        }
        final List<Object> rawAudioEffects = call.argument("androidAudioEffects");
        final TextureRegistry.SurfaceTextureEntry texture = textureRegistry.createSurfaceTexture();
        players.put(id, new AudioPlayer(applicationContext, messenger, id, call.argument("audioLoadConfiguration"),
          rawAudioEffects, call.argument("androidOffloadSchedulingEnabled"), texture));
        result.success(texture.id());
        break;
      }
      case "disposePlayer": {
        String id = call.argument("id");
        AudioPlayer player = players.get(id);
        if (player != null) {
          player.dispose();
          players.remove(id);
        }
        result.success(new HashMap<String, Object>());
        break;
      }
      case "disposeAllPlayers": {
        dispose();
        result.success(new HashMap<String, Object>());
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
