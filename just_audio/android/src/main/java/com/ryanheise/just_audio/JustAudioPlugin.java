package com.ryanheise.just_audio;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngine.EngineLifecycleListener;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.view.TextureRegistry;

/**
 * JustAudioPlugin
 */
@UnstableApi public class JustAudioPlugin implements FlutterPlugin, ActivityAware, PluginRegistry.NewIntentListener {
  private MethodChannel channel;
  private MainMethodCallHandler methodCallHandler;
  private Context applicationContext;
  private ActivityPluginBinding activityBinding;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    applicationContext = binding.getApplicationContext();
    BinaryMessenger messenger = binding.getBinaryMessenger();
    methodCallHandler = new MainMethodCallHandler(binding, messenger);

    channel = new MethodChannel(messenger, "com.ryanheise.just_audio.methods");
    channel.setMethodCallHandler(methodCallHandler);
    AudioOutputManager.get(binding.getApplicationContext()).attach(messenger);
    @SuppressWarnings("deprecation")
    FlutterEngine engine = binding.getFlutterEngine();
    engine.addEngineLifecycleListener(new EngineLifecycleListener() {
      @Override
      public void onPreEngineRestart() {
        methodCallHandler.dispose();
      }

      @Override
      public void onEngineWillDestroy() {}
    });
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    methodCallHandler.dispose();
    methodCallHandler = null;
    AudioOutputManager.get(binding.getApplicationContext()).detach(binding.getBinaryMessenger());

    channel.setMethodCallHandler(null);
  }

  // -- the system hands a usb dac to the app through the activity (USB_DEVICE_ATTACHED intent-filter), permission included
  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activityBinding = binding;
    binding.addOnNewIntentListener(this);
    onNewIntent(binding.getActivity().getIntent());
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivity() {
    if (activityBinding != null) activityBinding.removeOnNewIntentListener(this);
    activityBinding = null;
  }

  @Override
  public boolean onNewIntent(Intent intent) {
    if (intent == null || !UsbDirectManager.isDeviceAttachedIntent(intent)) return false;
    AudioOutputManager.get(applicationContext).onUsbDeviceHandedOver(intent);
    return false;
  }
}
