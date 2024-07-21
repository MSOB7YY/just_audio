import 'dart:async';

import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'method_channel_just_audio.dart';

/// The interface that implementations of just_audio must implement.
///
/// Platform implementations should extend this class rather than implement it
/// as `just_audio` does not consider newly added methods to be breaking
/// changes. Extending this class (using `extends`) ensures that the subclass
/// will get the default implementation, while platform implementations that
/// `implements` this interface will be broken by newly added
/// [JustAudioPlatform] methods.
abstract class JustAudioPlatform extends PlatformInterface {
  /// Constructs a JustAudioPlatform.
  JustAudioPlatform() : super(token: _token);

  static final Object _token = Object();

  static JustAudioPlatform _instance = MethodChannelJustAudio();

  /// The default instance of [JustAudioPlatform] to use.
  ///
  /// Defaults to [MethodChannelJustAudio].
  static JustAudioPlatform get instance => _instance;

  /// Platform-specific plugins should set this with their own platform-specific
  /// class that extends [JustAudioPlatform] when they register themselves.
  // TODO(amirh): Extract common platform interface logic.
  // https://github.com/flutter/flutter/issues/43368
  static set instance(JustAudioPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  /// Creates a new platform player and returns a nested platform interface for
  /// communicating with that player.
  Future<AudioPlayerPlatform> init(InitRequest request) {
    throw UnimplementedError('init() has not been implemented.');
  }

  /// Disposes of a platform player.
  Future<DisposePlayerResponse> disposePlayer(DisposePlayerRequest request) {
    throw UnimplementedError('disposePlayer() has not been implemented.');
  }

  /// Disposes of all platform players.
  Future<DisposeAllPlayersResponse> disposeAllPlayers(
      DisposeAllPlayersRequest request) {
    throw UnimplementedError('disposeAllPlayers() has not been implemented.');
  }
}

/// A nested platform interface for communicating with a particular player
/// instance.
///
/// Platform implementations should extend this class rather than implement it
/// as `just_audio` does not consider newly added methods to be breaking
/// changes. Extending this class (using `extends`) ensures that the subclass
/// will get the default implementation, while platform implementations that
/// `implements` this interface will be broken by newly added
/// [AudioPlayerPlatform] methods.
abstract class AudioPlayerPlatform {
  final String id;

  const AudioPlayerPlatform(this.id);

  /// A broadcast stream of playback events.
  Stream<PlaybackEventMessage> get playbackEventMessageStream {
    throw UnimplementedError(
        'playbackEventMessageStream has not been implemented.');
  }

  /// A broadcast stream of data updates.
  Stream<PlayerDataMessage> get playerDataMessageStream =>
      const Stream<PlayerDataMessage>.empty();

  /// A broadcast stream of video updates.
  Stream<VideoDataMessage> get videoDataMessageStream =>
      const Stream<VideoDataMessage>.empty();

  /// Loads an audio source.
  Future<LoadResponse> load(LoadRequest request) {
    throw UnimplementedError("load() has not been implemented.");
  }

  /// Set a video source, use null to disable video.
  Future<void> setVideo(VideoLoadRequest? video) {
    throw UnimplementedError("setVideo() has not been implemented.");
  }

  /// Retrieves the currently active preset.
  Future<int?> getCurrentPreset() async {
    throw UnimplementedError("getCurrentPreset() has not been implemented.");
  }

  /// Retrieves a list of supported equalizer presets.
  Future<List<String>> getEqualizerPresets() async {
    throw UnimplementedError("getEqualizerPresets() has not been implemented.");
  }

  /// Sets a preset using the index retrieved in [getEqualizerPresets].
  Future<int?> setEqualizerPreset(int index) async {
    throw UnimplementedError("setEqualizerPreset() has not been implemented.");
  }

  /// Plays the current audio source at the current index and position.
  Future<PlayResponse> play(PlayRequest request) {
    throw UnimplementedError("play() has not been implemented.");
  }

  /// Pauses playback.
  Future<PauseResponse> pause(PauseRequest request) {
    throw UnimplementedError("pause() has not been implemented.");
  }

  /// Changes the volume.
  Future<SetVolumeResponse> setVolume(SetVolumeRequest request) {
    throw UnimplementedError("setVolume() has not been implemented.");
  }

  /// Changes the playback speed.
  Future<SetSpeedResponse> setSpeed(SetSpeedRequest request) {
    throw UnimplementedError("setSpeed() has not been implemented.");
  }

  /// Changes the pitch.
  Future<SetPitchResponse> setPitch(SetPitchRequest request) {
    throw UnimplementedError("setPitch() has not been implemented.");
  }

  /// Sets skipSilence to true/false.
  Future<SetSkipSilenceResponse> setSkipSilence(SetSkipSilenceRequest request) {
    throw UnimplementedError("setSkipSilence() has not been implemented.");
  }

  /// Sets the loop mode.
  Future<SetLoopModeResponse> setLoopMode(SetLoopModeRequest request) {
    throw UnimplementedError("setLoopMode() has not been implemented.");
  }

  /// Sets the shuffle mode.
  Future<SetShuffleModeResponse> setShuffleMode(SetShuffleModeRequest request) {
    throw UnimplementedError("setShuffleMode() has not been implemented.");
  }

  /// Sets the shuffle order.
  Future<SetShuffleOrderResponse> setShuffleOrder(
      SetShuffleOrderRequest request) {
    throw UnimplementedError("setShuffleOrder() has not been implemented.");
  }

  /// On iOS and macOS, sets the automaticallyWaitsToMinimizeStalling option,
  /// and does nothing on other platforms.
  Future<SetAutomaticallyWaitsToMinimizeStallingResponse>
      setAutomaticallyWaitsToMinimizeStalling(
          SetAutomaticallyWaitsToMinimizeStallingRequest request) {
    throw UnimplementedError(
        "setAutomaticallyWaitsToMinimizeStalling() has not been implemented.");
  }

  /// On iOS and macOS, sets the
  /// canUseNetworkResourcesForLiveStreamingWhilePaused option, and does nothing
  /// on other platforms.
  Future<SetCanUseNetworkResourcesForLiveStreamingWhilePausedResponse>
      setCanUseNetworkResourcesForLiveStreamingWhilePaused(
          SetCanUseNetworkResourcesForLiveStreamingWhilePausedRequest request) {
    throw UnimplementedError(
        "setCanUseNetworkResourcesForLiveStreamingWhilePaused() has not been implemented.");
  }

  /// On iOS and macOS, sets the preferredPeakBitRate option, and does nothing
  /// on other platforms.
  Future<SetPreferredPeakBitRateResponse> setPreferredPeakBitRate(
      SetPreferredPeakBitRateRequest request) {
    throw UnimplementedError(
        "setPreferredPeakBitRate() has not been implemented.");
  }

  /// Seeks to the given index and position.
  Future<SeekResponse> seek(SeekRequest request) {
    throw UnimplementedError("seek() has not been implemented.");
  }

  /// On Android, sets the audio attributes, and does nothing on other
  /// platforms.
  Future<SetAndroidAudioAttributesResponse> setAndroidAudioAttributes(
      SetAndroidAudioAttributesRequest request) {
    throw UnimplementedError(
        "setAndroidAudioAttributes() has not been implemented.");
  }

  /// This method has been superseded by [JustAudioPlatform.disposePlayer].
  /// For backward compatibility, this method will still be called as a
  /// fallback if [JustAudioPlatform.disposePlayer] is not implemented.
  Future<DisposeResponse> dispose(DisposeRequest request) {
    throw UnimplementedError("dispose() has not been implemented.");
  }

  /// Inserts audio sources into the given concatenating audio source.
  Future<ConcatenatingInsertAllResponse> concatenatingInsertAll(
      ConcatenatingInsertAllRequest request) {
    throw UnimplementedError(
        "concatenatingInsertAll() has not been implemented.");
  }

  /// Removes audio sources from the given concatenating audio source.
  Future<ConcatenatingRemoveRangeResponse> concatenatingRemoveRange(
      ConcatenatingRemoveRangeRequest request) {
    throw UnimplementedError(
        "concatenatingRemoveRange() has not been implemented.");
  }

  /// Moves an audio source within a concatenating audio source.
  Future<ConcatenatingMoveResponse> concatenatingMove(
      ConcatenatingMoveRequest request) {
    throw UnimplementedError("concatenatingMove() has not been implemented.");
  }

  /// Changes the enabled status of an audio effect.
  Future<AudioEffectSetEnabledResponse> audioEffectSetEnabled(
      AudioEffectSetEnabledRequest request) {
    throw UnimplementedError(
        "audioEffectSetEnabled() has not been implemented.");
  }

  /// Sets the target gain on the Android loudness enhancer.
  Future<AndroidLoudnessEnhancerSetTargetGainResponse>
      androidLoudnessEnhancerSetTargetGain(
          AndroidLoudnessEnhancerSetTargetGainRequest request) {
    throw UnimplementedError(
        "androidLoudnessEnhancerSetTargetGain() has not been implemented.");
  }

  /// Gets the Android equalizer parameters.
  Future<AndroidEqualizerGetParametersResponse> androidEqualizerGetParameters(
      AndroidEqualizerGetParametersRequest request) {
    throw UnimplementedError(
        "androidEqualizerGetParameters() has not been implemented.");
  }

  /// Sets the gain for an Android equalizer band.
  Future<AndroidEqualizerBandSetGainResponse> androidEqualizerBandSetGain(
      AndroidEqualizerBandSetGainRequest request) {
    throw UnimplementedError(
        "androidEqualizerBandSetGain() has not been implemented.");
  }
}

/// A data update communicated from the platform implementation to the Flutter
/// plugin. Each field should trigger a state update in the frontend plugin if
/// and only if it is not null. Normally, the platform implementation will not
/// need to broadcast new state changes for this state as such state changes
/// will be initiated from the frontend.
class PlayerDataMessage {
  final bool? playing;
  final double? volume;
  final double? speed;
  final double? pitch;
  final LoopModeMessage? loopMode;
  final ShuffleModeMessage? shuffleMode;
  // TODO: Eventually move other state here?
  // bufferedPosition, androidAudioSessionId, icyMetadata

  PlayerDataMessage({
    this.playing,
    this.volume,
    this.speed,
    this.pitch,
    this.loopMode,
    this.shuffleMode,
  });

  static PlayerDataMessage fromMap(Map<dynamic, dynamic> map) =>
      PlayerDataMessage(
        playing: map['playing'] as bool?,
        volume: map['volume'] as double?,
        speed: map['speed'] as double?,
        pitch: map['pitch'] as double?,
        loopMode: map['loopMode'] != null
            ? LoopModeMessage.values[map['loopMode'] as int]
            : null,
        shuffleMode: map['shuffleMode'] != null
            ? ShuffleModeMessage.values[map['shuffleMode'] as int]
            : null,
      );
}

class VideoDataMessage {
  final String id;
  final int textureId;
  final int width;
  final int height;
  final double frameRate;
  final int bitrate;
  final int sampleRate;
  final int encoderDelay;
  final int rotationDegrees;
  final String containerMimeType;
  final String label;
  final String language;

  bool get isInitialized => textureId != -1;

  double get aspectRatio {
    if (!isInitialized) return 1.0;
    if (width == 0 || height == 0) return 1.0;

    final double aspectRatio = width / height;
    if (aspectRatio <= 0) return 1.0;

    return aspectRatio;
  }

  const VideoDataMessage({
    required this.id,
    required this.textureId,
    required this.width,
    required this.height,
    required this.frameRate,
    required this.bitrate,
    required this.sampleRate,
    required this.encoderDelay,
    required this.rotationDegrees,
    required this.containerMimeType,
    required this.label,
    required this.language,
  });

  factory VideoDataMessage.dummy() {
    return const VideoDataMessage(
      id: '',
      textureId: -1,
      width: -1,
      height: -1,
      frameRate: -1,
      bitrate: -1,
      sampleRate: -1,
      encoderDelay: -1,
      rotationDegrees: -1,
      containerMimeType: '',
      label: '',
      language: '',
    );
  }

  static VideoDataMessage fromMap(Map<dynamic, dynamic> map) {
    return VideoDataMessage(
      id: map["id"] as String? ?? '',
      textureId: map["textureId"] as int? ?? -1,
      width: map["width"] as int? ?? -1,
      height: map["height"] as int? ?? -1,
      frameRate: map["frameRate"] as double? ?? -1,
      bitrate: map["bitrate"] as int? ?? -1,
      sampleRate: map["sampleRate"] as int? ?? -1,
      encoderDelay: map["encoderDelay"] as int? ?? -1,
      rotationDegrees: map["rotationDegrees"] as int? ?? -1,
      containerMimeType: map["containerMimeType"] as String? ?? '',
      label: map["label"] as String? ?? '',
      language: map["language"] as String? ?? '',
    );
  }
}

/// A playback event communicated from the platform implementation to the
/// Flutter plugin.
class PlaybackEventMessage {
  final ProcessingStateMessage processingState;
  final DateTime updateTime;
  final Duration updatePosition;
  final Duration bufferedPosition;
  final Duration? duration;
  final IcyMetadataMessage? icyMetadata;
  final int? currentIndex;
  final int? androidAudioSessionId;

  const PlaybackEventMessage({
    required this.processingState,
    required this.updateTime,
    required this.updatePosition,
    required this.bufferedPosition,
    required this.duration,
    required this.icyMetadata,
    required this.currentIndex,
    this.androidAudioSessionId,
  });

  static PlaybackEventMessage fromMap(Map<dynamic, dynamic> map) =>
      PlaybackEventMessage(
        processingState:
            ProcessingStateMessage.values[map['processingState'] as int],
        updateTime:
            DateTime.fromMillisecondsSinceEpoch(map['updateTime'] as int),
        updatePosition: Duration(microseconds: map['updatePosition'] as int),
        bufferedPosition:
            Duration(microseconds: map['bufferedPosition'] as int),
        duration: map['duration'] == null || map['duration'] as int < 0
            ? null
            : Duration(microseconds: map['duration'] as int),
        icyMetadata: map['icyMetadata'] == null
            ? null
            : IcyMetadataMessage.fromMap(
                map['icyMetadata'] as Map<dynamic, dynamic>),
        currentIndex: map['currentIndex'] as int?,
        androidAudioSessionId: map['androidAudioSessionId'] as int?,
      );
}

/// A processing state communicated from the platform implementation.
enum ProcessingStateMessage {
  idle,
  loading,
  buffering,
  ready,
  completed,
}

/// Icy metadata communicated from the platform implementation.
class IcyMetadataMessage {
  final IcyInfoMessage? info;
  final IcyHeadersMessage? headers;

  const IcyMetadataMessage({
    required this.info,
    required this.headers,
  });

  static IcyMetadataMessage fromMap(Map<dynamic, dynamic> json) =>
      IcyMetadataMessage(
        info: json['info'] == null
            ? null
            : IcyInfoMessage.fromMap(json['info'] as Map<dynamic, dynamic>),
        headers: json['headers'] == null
            ? null
            : IcyHeadersMessage.fromMap(
                json['headers'] as Map<dynamic, dynamic>),
      );
}

/// Icy info communicated from the platform implementation.
class IcyInfoMessage {
  final String? title;
  final String? url;

  const IcyInfoMessage({
    required this.title,
    required this.url,
  });

  static IcyInfoMessage fromMap(Map<dynamic, dynamic> json) => IcyInfoMessage(
      title: json['title'] as String?, url: json['url'] as String?);
}

/// Icy headers communicated from the platform implementation.
class IcyHeadersMessage {
  final int? bitrate;
  final String? genre;
  final String? name;
  final int? metadataInterval;
  final String? url;
  final bool? isPublic;

  const IcyHeadersMessage({
    required this.bitrate,
    required this.genre,
    required this.name,
    required this.metadataInterval,
    required this.url,
    required this.isPublic,
  });

  static IcyHeadersMessage fromMap(Map<dynamic, dynamic> json) =>
      IcyHeadersMessage(
        bitrate: json['bitrate'] as int?,
        genre: json['genre'] as String?,
        name: json['name'] as String?,
        metadataInterval: json['metadataInterval'] as int?,
        url: json['url'] as String?,
        isPublic: json['isPublic'] as bool?,
      );
}

/// Information communicated to the platform implementation when creating a new
/// player instance.
class InitRequest {
  final String id;
  final AudioLoadConfigurationMessage? audioLoadConfiguration;
  final List<AudioEffectMessage> androidAudioEffects;
  final List<AudioEffectMessage> darwinAudioEffects;

  const InitRequest({
    required this.id,
    this.audioLoadConfiguration,
    this.androidAudioEffects = const [],
    this.darwinAudioEffects = const [],
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'id': id,
        'audioLoadConfiguration': audioLoadConfiguration?.toMap(),
        'androidAudioEffects': androidAudioEffects
            .map((audioEffect) => audioEffect.toMap())
            .toList(),
        'darwinAudioEffects': darwinAudioEffects
            .map((audioEffect) => audioEffect.toMap())
            .toList(),
      };
}

/// Information communicated to the platform implementation when disposing of a
/// player instance.
class DisposePlayerRequest {
  final String id;

  const DisposePlayerRequest({required this.id});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'id': id,
      };
}

/// Information returned by the platform implementation after disposing of a
/// player instance.
class DisposePlayerResponse {
  static DisposePlayerResponse fromMap(Map<dynamic, dynamic> map) =>
      DisposePlayerResponse();
}

/// Information communicated to the platform implementation when disposing of all
/// player instances.
class DisposeAllPlayersRequest {
  const DisposeAllPlayersRequest();

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{};
}

/// Information returned by the platform implementation after disposing of all
/// player instances.
class DisposeAllPlayersResponse {
  static DisposeAllPlayersResponse fromMap(Map<dynamic, dynamic> map) =>
      DisposeAllPlayersResponse();
}

/// Information communicated to the platform implementation when loading an
/// audio source.
class LoadRequest {
  final SourceMessage audioSourceMessage;
  final VideoLoadRequest? videoRequest;
  final Duration? initialPosition;
  final int? initialIndex;
  final bool keepOldVideoSource;

  const LoadRequest({
    required this.audioSourceMessage,
    required this.videoRequest,
    required this.initialPosition,
    required this.initialIndex,
    required this.keepOldVideoSource,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'audioSource': audioSourceMessage.toMap(),
        'videoOptions': videoRequest?.toMap(),
        'initialPosition': initialPosition?.inMicroseconds,
        'initialIndex': initialIndex,
        'keepOldVideoSource': keepOldVideoSource,
      };
}

class VideoLoadRequest {
  final SourceMessage videoSourceMessage;
  final bool videoOnly;
  final bool videoLoop;

  const VideoLoadRequest({
    required this.videoSourceMessage,
    required this.videoOnly,
    required this.videoLoop,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'videoSource': videoSourceMessage.toMap(),
        'videoOnly': videoOnly,
        'loop': videoLoop,
      };
}

/// Information returned by the platform implementation after loading an audio
/// source.
class LoadResponse {
  final Duration? duration;

  const LoadResponse({required this.duration});

  static LoadResponse fromMap(Map<dynamic, dynamic> map) => LoadResponse(
      duration: map['duration'] == null || map['duration'] as int < 0
          ? null
          : Duration(microseconds: map['duration'] as int));
}

/// Information communicated to the platform implementation when playing an
/// audio source.
class PlayRequest {
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{};
}

/// Information returned by the platform implementation after playing an audio
/// source.
class PlayResponse {
  static PlayResponse fromMap(Map<dynamic, dynamic> map) => PlayResponse();
}

/// Information communicated to the platform implementation when pausing
/// playback.
class PauseRequest {
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{};
}

/// Information returned by the platform implementation after pausing playback.
class PauseResponse {
  static PauseResponse fromMap(Map<dynamic, dynamic> map) => PauseResponse();
}

/// Information communicated to the platform implementation when setting the
/// volume.
class SetVolumeRequest {
  final double volume;

  const SetVolumeRequest({required this.volume});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'volume': volume,
      };
}

/// Information returned by the platform implementation after setting the
/// volume.
class SetVolumeResponse {
  static SetVolumeResponse fromMap(Map<dynamic, dynamic> map) =>
      SetVolumeResponse();
}

/// Information communicated to the platform implementation when setting the
/// speed.
class SetSpeedRequest {
  final double speed;

  const SetSpeedRequest({required this.speed});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'speed': speed,
      };
}

/// Information returned by the platform implementation after setting the
/// speed.
class SetSpeedResponse {
  static SetSpeedResponse fromMap(Map<dynamic, dynamic> map) =>
      SetSpeedResponse();
}

/// Information communicated to the platform implementation when setting the
/// pitch.
class SetPitchRequest {
  final double pitch;

  const SetPitchRequest({required this.pitch});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'pitch': pitch,
      };
}

/// Information returned by the platform implementation after setting the
/// pitch.
class SetPitchResponse {
  static SetPitchResponse fromMap(Map<dynamic, dynamic> map) =>
      SetPitchResponse();
}

/// Information communicated to the platform implementation when setting the
/// skipSilence.
class SetSkipSilenceRequest {
  final bool enabled;

  const SetSkipSilenceRequest({required this.enabled});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'enabled': enabled,
      };
}

/// Information returned by the platform implementation after setting the
/// speed.
class SetSkipSilenceResponse {
  static SetSkipSilenceResponse fromMap(Map<dynamic, dynamic> map) =>
      SetSkipSilenceResponse();
}

/// Information communicated to the platform implementation when setting the
/// loop mode.
class SetLoopModeRequest {
  final LoopModeMessage loopMode;

  const SetLoopModeRequest({required this.loopMode});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'loopMode': loopMode.index,
      };
}

/// Information returned by the platform implementation after setting the
/// loop mode.
class SetLoopModeResponse {
  static SetLoopModeResponse fromMap(Map<dynamic, dynamic> map) =>
      SetLoopModeResponse();
}

/// The loop mode communicated to the platform implementation.
enum LoopModeMessage { off, one, all }

/// Information communicated to the platform implementation when setting the
/// shuffle mode.
class SetShuffleModeRequest {
  final ShuffleModeMessage shuffleMode;

  const SetShuffleModeRequest({required this.shuffleMode});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'shuffleMode': shuffleMode.index,
      };
}

/// Information returned by the platform implementation after setting the
/// shuffle mode.
class SetShuffleModeResponse {
  static SetShuffleModeResponse fromMap(Map<dynamic, dynamic> map) =>
      SetShuffleModeResponse();
}

/// The shuffle mode communicated to the platform implementation.
enum ShuffleModeMessage { none, all }

/// Information communicated to the platform implementation when setting the
/// shuffle order.
class SetShuffleOrderRequest {
  final SourceMessage audioSourceMessage;

  const SetShuffleOrderRequest({required this.audioSourceMessage});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'audioSource': audioSourceMessage.toMap(),
      };
}

/// Information returned by the platform implementation after setting the
/// shuffle order.
class SetShuffleOrderResponse {
  static SetShuffleOrderResponse fromMap(Map<dynamic, dynamic> map) =>
      SetShuffleOrderResponse();
}

/// Information communicated to the platform implementation when setting the
/// automaticallyWaitsToMinimizeStalling option.
class SetAutomaticallyWaitsToMinimizeStallingRequest {
  final bool enabled;

  const SetAutomaticallyWaitsToMinimizeStallingRequest({required this.enabled});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'enabled': enabled,
      };
}

/// Information returned by the platform implementation after setting the
/// automaticallyWaitsToMinimizeStalling option.
class SetAutomaticallyWaitsToMinimizeStallingResponse {
  static SetAutomaticallyWaitsToMinimizeStallingResponse fromMap(
          Map<dynamic, dynamic> map) =>
      SetAutomaticallyWaitsToMinimizeStallingResponse();
}

/// Information communicated to the platform implementation when setting the
/// canUseNetworkResourcesForLiveStreamingWhilePaused option.
class SetCanUseNetworkResourcesForLiveStreamingWhilePausedRequest {
  final bool enabled;

  const SetCanUseNetworkResourcesForLiveStreamingWhilePausedRequest(
      {required this.enabled});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'enabled': enabled,
      };
}

/// Information returned by the platform implementation after setting the
/// canUseNetworkResourcesForLiveStreamingWhilePaused option.
class SetCanUseNetworkResourcesForLiveStreamingWhilePausedResponse {
  static SetCanUseNetworkResourcesForLiveStreamingWhilePausedResponse fromMap(
          Map<dynamic, dynamic> map) =>
      SetCanUseNetworkResourcesForLiveStreamingWhilePausedResponse();
}

/// Information communicated to the platform implementation when setting the
/// automaticallyWaitsToMinimizeStalling option.
class SetPreferredPeakBitRateRequest {
  final double bitRate;

  const SetPreferredPeakBitRateRequest({required this.bitRate});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'bitRate': bitRate,
      };
}

/// Information returned by the platform implementation after setting the
/// automaticallyWaitsToMinimizeStalling option.
class SetPreferredPeakBitRateResponse {
  static SetPreferredPeakBitRateResponse fromMap(Map<dynamic, dynamic> map) =>
      SetPreferredPeakBitRateResponse();
}

/// Information communicated to the platform implementation when seeking to a
/// position and index.
class SeekRequest {
  final Duration? position;
  final int? index;

  const SeekRequest({this.position, this.index});

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'position': position?.inMicroseconds,
        'index': index,
      };
}

/// Information returned by the platform implementation after seeking to a
/// position and index.
class SeekResponse {
  static SeekResponse fromMap(Map<dynamic, dynamic> map) => SeekResponse();
}

/// Information communicated to the platform implementation when setting the
/// Android audio attributes.
class SetAndroidAudioAttributesRequest {
  final int contentType;
  final int flags;
  final int usage;

  const SetAndroidAudioAttributesRequest({
    required this.contentType,
    required this.flags,
    required this.usage,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'contentType': contentType,
        'flags': flags,
        'usage': usage,
      };
}

/// Information returned by the platform implementation after setting the
/// Android audio attributes.
class SetAndroidAudioAttributesResponse {
  static SetAndroidAudioAttributesResponse fromMap(Map<dynamic, dynamic> map) =>
      SetAndroidAudioAttributesResponse();
}

/// The parameter of [AudioPlayerPlatform.dispose] which is deprecated.
class DisposeRequest {
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{};
}

/// The result of [AudioPlayerPlatform.dispose] which is deprecated.
class DisposeResponse {
  static DisposeResponse fromMap(Map<dynamic, dynamic> map) =>
      DisposeResponse();
}

/// Information communicated to the platform implementation when inserting audio
/// sources into a concatenating audio source.
class ConcatenatingInsertAllRequest {
  final String id;
  final int index;
  final List<SourceMessage> children;
  final List<int> shuffleOrder;

  const ConcatenatingInsertAllRequest({
    required this.id,
    required this.index,
    required this.children,
    required this.shuffleOrder,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'id': id,
        'index': index,
        'children': children.map((child) => child.toMap()).toList(),
        'shuffleOrder': shuffleOrder,
      };
}

/// Information returned by the platform implementation after inserting audio
/// sources into a concatenating audio source.
class ConcatenatingInsertAllResponse {
  static ConcatenatingInsertAllResponse fromMap(Map<dynamic, dynamic> map) =>
      ConcatenatingInsertAllResponse();
}

/// Information communicated to the platform implementation when removing audio
/// sources from a concatenating audio source.
class ConcatenatingRemoveRangeRequest {
  final String id;
  final int startIndex;
  final int endIndex;
  final List<int> shuffleOrder;

  const ConcatenatingRemoveRangeRequest({
    required this.id,
    required this.startIndex,
    required this.endIndex,
    required this.shuffleOrder,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'id': id,
        'startIndex': startIndex,
        'endIndex': endIndex,
        'shuffleOrder': shuffleOrder,
      };
}

/// Information returned by the platform implementation after removing audio
/// sources from a concatenating audio source.
class ConcatenatingRemoveRangeResponse {
  static ConcatenatingRemoveRangeResponse fromMap(Map<dynamic, dynamic> map) =>
      ConcatenatingRemoveRangeResponse();
}

/// Information communicated to the platform implementation when moving an audio
/// source within a concatenating audio source.
class ConcatenatingMoveRequest {
  final String id;
  final int currentIndex;
  final int newIndex;
  final List<int> shuffleOrder;

  const ConcatenatingMoveRequest({
    required this.id,
    required this.currentIndex,
    required this.newIndex,
    required this.shuffleOrder,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'id': id,
        'currentIndex': currentIndex,
        'newIndex': newIndex,
        'shuffleOrder': shuffleOrder,
      };
}

/// Information returned by the platform implementation after moving an audio
/// source within a concatenating audio source.
class ConcatenatingMoveResponse {
  static ConcatenatingMoveResponse fromMap(Map<dynamic, dynamic> map) =>
      ConcatenatingMoveResponse();
}

/// Information communicated to the platform implementation when setting the
/// audio load configuration options.
class AudioLoadConfigurationMessage {
  final DarwinLoadControlMessage? darwinLoadControl;
  final AndroidLoadControlMessage? androidLoadControl;
  final AndroidLivePlaybackSpeedControlMessage? androidLivePlaybackSpeedControl;

  const AudioLoadConfigurationMessage({
    required this.darwinLoadControl,
    required this.androidLoadControl,
    required this.androidLivePlaybackSpeedControl,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'darwinLoadControl': darwinLoadControl?.toMap(),
        'androidLoadControl': androidLoadControl?.toMap(),
        'androidLivePlaybackSpeedControl':
            androidLivePlaybackSpeedControl?.toMap(),
      };
}

class DarwinLoadControlMessage {
  /// (iOS/macOS) Whether the player will wait for sufficient data to be
  /// buffered before starting playback to avoid the likelihood of stalling.
  final bool automaticallyWaitsToMinimizeStalling;

  /// (iOS/macOS) The duration of audio that should be buffered ahead of the
  /// current position. If not set or `null`, the system will try to set an
  /// appropriate buffer duration.
  final Duration? preferredForwardBufferDuration;

  /// (iOS/macOS) Whether the player can continue downloading while paused to
  /// keep the state up to date with the live stream.
  final bool canUseNetworkResourcesForLiveStreamingWhilePaused;

  /// (iOS/macOS) If specified, limits the download bandwidth in bits per
  /// second.
  final double? preferredPeakBitRate;

  const DarwinLoadControlMessage({
    required this.automaticallyWaitsToMinimizeStalling,
    required this.preferredForwardBufferDuration,
    required this.canUseNetworkResourcesForLiveStreamingWhilePaused,
    required this.preferredPeakBitRate,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'automaticallyWaitsToMinimizeStalling':
            automaticallyWaitsToMinimizeStalling,
        'preferredForwardBufferDuration':
            preferredForwardBufferDuration?.inMicroseconds,
        'canUseNetworkResourcesForLiveStreamingWhilePaused':
            canUseNetworkResourcesForLiveStreamingWhilePaused,
        'preferredPeakBitRate': preferredPeakBitRate,
      };
}

class AndroidLoadControlMessage {
  /// (Android) The minimum duration of audio that should be buffered ahead of
  /// the current position.
  final Duration minBufferDuration;

  /// (Android) The maximum duration of audio that should be buffered ahead of
  /// the current position.
  final Duration maxBufferDuration;

  /// (Android) The duration of audio that must be buffered before starting
  /// playback after a user action.
  final Duration bufferForPlaybackDuration;

  /// (Android) The duration of audio that must be buffered before starting
  /// playback after a buffer depletion.
  final Duration bufferForPlaybackAfterRebufferDuration;

  /// (Android) The target buffer size in bytes.
  final int? targetBufferBytes;

  /// (Android) Whether to prioritize buffer time constraints over buffer size
  /// constraints.
  final bool prioritizeTimeOverSizeThresholds;

  /// (Android) The back buffer duration.
  final Duration backBufferDuration;

  const AndroidLoadControlMessage({
    required this.minBufferDuration,
    required this.maxBufferDuration,
    required this.bufferForPlaybackDuration,
    required this.bufferForPlaybackAfterRebufferDuration,
    required this.targetBufferBytes,
    required this.prioritizeTimeOverSizeThresholds,
    required this.backBufferDuration,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'minBufferDuration': minBufferDuration.inMicroseconds,
        'maxBufferDuration': maxBufferDuration.inMicroseconds,
        'bufferForPlaybackDuration': bufferForPlaybackDuration.inMicroseconds,
        'bufferForPlaybackAfterRebufferDuration':
            bufferForPlaybackAfterRebufferDuration.inMicroseconds,
        'targetBufferBytes': targetBufferBytes,
        'prioritizeTimeOverSizeThresholds': prioritizeTimeOverSizeThresholds,
        'backBufferDuration': backBufferDuration.inMicroseconds,
      };
}

class AndroidLivePlaybackSpeedControlMessage {
  /// (Android) The minimum playback speed to use when adjusting playback speed
  /// to approach the target live offset, if none is defined by the media.
  final double fallbackMinPlaybackSpeed;

  /// (Android) The maximum playback speed to use when adjusting playback speed
  /// to approach the target live offset, if none is defined by the media.
  final double fallbackMaxPlaybackSpeed;

  /// (Android) The minimum interval between playback speed changes on a live
  /// stream.
  final Duration minUpdateInterval;

  /// (Android) The proportional control factor used to adjust playback speed on
  /// a live stream. The adjusted speed is calculated as: `1.0 +
  /// proportionalControlFactor * (currentLiveOffsetSec - targetLiveOffsetSec)`.
  final double proportionalControlFactor;

  /// (Android) The maximum difference between the current live offset and the
  /// target live offset within which the speed 1.0 is used.
  final Duration maxLiveOffsetErrorForUnitSpeed;

  /// (Android) The increment applied to the target live offset whenever the
  /// player rebuffers.
  final Duration targetLiveOffsetIncrementOnRebuffer;

  /// (Android) The factor for smoothing the minimum possible live offset
  /// achievable during playback.
  final double minPossibleLiveOffsetSmoothingFactor;

  const AndroidLivePlaybackSpeedControlMessage({
    required this.fallbackMinPlaybackSpeed,
    required this.fallbackMaxPlaybackSpeed,
    required this.minUpdateInterval,
    required this.proportionalControlFactor,
    required this.maxLiveOffsetErrorForUnitSpeed,
    required this.targetLiveOffsetIncrementOnRebuffer,
    required this.minPossibleLiveOffsetSmoothingFactor,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'fallbackMinPlaybackSpeed': fallbackMinPlaybackSpeed,
        'fallbackMaxPlaybackSpeed': fallbackMaxPlaybackSpeed,
        'minUpdateInterval': minUpdateInterval.inMicroseconds,
        'proportionalControlFactor': proportionalControlFactor,
        'maxLiveOffsetErrorForUnitSpeed':
            maxLiveOffsetErrorForUnitSpeed.inMicroseconds,
        'targetLiveOffsetIncrementOnRebuffer':
            targetLiveOffsetIncrementOnRebuffer.inMicroseconds,
        'minPossibleLiveOffsetSmoothingFactor':
            minPossibleLiveOffsetSmoothingFactor,
      };
}

/// Information about an audio source to be communicated with the platform
/// implementation.
abstract class SourceMessage {
  final String id;

  const SourceMessage({required this.id});

  Map<dynamic, dynamic> toMap();
}

/// Information about an indexed audio source to be communicated with the
/// platform implementation.
abstract class IndexedSourceMessage extends SourceMessage {
  /// Since the tag type is unknown, this can only be used by platform
  /// implementations that pass by reference.
  final dynamic tag;
  const IndexedSourceMessage({required super.id, this.tag});
}

/// Information about a URI audio source to be communicated with the platform
/// implementation.
abstract class UriSourceMessage extends IndexedSourceMessage {
  final String uri;
  final Map<String, String>? headers;

  const UriSourceMessage({
    required super.id,
    required this.uri,
    this.headers,
    super.tag,
  });
}

/// Information about a progressive audio source to be communicated with the
/// platform implementation.
class ProgressiveSourceMessage extends UriSourceMessage {
  const ProgressiveSourceMessage({
    required super.id,
    required super.uri,
    super.headers,
    super.tag,
  });

  @override
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': 'progressive',
        'id': id,
        'uri': uri,
        'headers': headers,
      };
}

/// Information about a DASH audio source to be communicated with the platform
/// implementation.
class DashSourceMessage extends UriSourceMessage {
  const DashSourceMessage({
    required super.id,
    required super.uri,
    super.headers,
    super.tag,
  });

  @override
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': 'dash',
        'id': id,
        'uri': uri,
        'headers': headers,
      };
}

/// Information about a HLS audio source to be communicated with the platform
/// implementation.
class HlsSourceMessage extends UriSourceMessage {
  const HlsSourceMessage({
    required super.id,
    required super.uri,
    super.headers,
    super.tag,
  });

  @override
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': 'hls',
        'id': id,
        'uri': uri,
        'headers': headers,
      };
}

/// Information about a silence audio source to be communicated with the
/// platform implementation.
class SilenceSourceMessage extends IndexedSourceMessage {
  final Duration duration;

  const SilenceSourceMessage({
    required super.id,
    required this.duration,
  });

  @override
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': 'silence',
        'id': id,
        'duration': duration.inMicroseconds,
      };
}

/// Information about a concatenating audio source to be communicated with the
/// platform implementation.
class ConcatenatingSourceMessage extends SourceMessage {
  final List<SourceMessage> children;
  final bool useLazyPreparation;
  final List<int> shuffleOrder;

  const ConcatenatingSourceMessage({
    required super.id,
    required this.children,
    required this.useLazyPreparation,
    required this.shuffleOrder,
  });

  @override
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': 'concatenating',
        'id': id,
        'children': children.map((child) => child.toMap()).toList(),
        'useLazyPreparation': useLazyPreparation,
        'shuffleOrder': shuffleOrder,
      };
}

/// Information about a clipping audio source to be communicated with the
/// platform implementation.
class ClippingSourceMessage extends IndexedSourceMessage {
  final UriSourceMessage child;
  final Duration? start;
  final Duration? end;

  const ClippingSourceMessage({
    required super.id,
    required this.child,
    this.start,
    this.end,
    super.tag,
  });

  @override
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': 'clipping',
        'id': id,
        'child': child.toMap(),
        'start': start?.inMicroseconds,
        'end': end?.inMicroseconds,
      };
}

/// Information about a looping audio source to be communicated with the
/// platform implementation.
class LoopingSourceMessage extends SourceMessage {
  final SourceMessage child;
  final int count;

  const LoopingSourceMessage({
    required super.id,
    required this.child,
    required this.count,
  });

  @override
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': 'looping',
        'id': id,
        'child': child.toMap(),
        'count': count,
      };
}

/// Information communicated to the platform implementation when setting the
/// enabled status of an audio effect.
class AudioEffectSetEnabledRequest {
  final String type;
  final bool enabled;

  const AudioEffectSetEnabledRequest({
    required this.type,
    required this.enabled,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': type,
        'enabled': enabled,
      };
}

/// Information returned by the platform implementation after setting the
/// enabled status of an audio effect.
class AudioEffectSetEnabledResponse {
  static AudioEffectSetEnabledResponse fromMap(Map<dynamic, dynamic> map) =>
      AudioEffectSetEnabledResponse();
}

/// Information communicated to the platform implementation when setting the
/// target gain on the loudness enhancer audio effect.
class AndroidLoudnessEnhancerSetTargetGainRequest {
  /// The target gain in decibels.
  final double targetGain;

  const AndroidLoudnessEnhancerSetTargetGainRequest({
    required this.targetGain,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'targetGain': targetGain,
      };
}

/// Information returned by the platform implementation after setting the target
/// gain on the loudness enhancer audio effect.
class AndroidLoudnessEnhancerSetTargetGainResponse {
  static AndroidLoudnessEnhancerSetTargetGainResponse fromMap(
          Map<dynamic, dynamic> map) =>
      AndroidLoudnessEnhancerSetTargetGainResponse();
}

/// Information communicated to the platform implementation when requesting the
/// equalizer parameters.
class AndroidEqualizerGetParametersRequest {
  const AndroidEqualizerGetParametersRequest();

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{};
}

/// Information communicated to the platform implementation after requesting the
/// equalizer parameters.
class AndroidEqualizerGetParametersResponse {
  final AndroidEqualizerParametersMessage parameters;

  const AndroidEqualizerGetParametersResponse({required this.parameters});

  static AndroidEqualizerGetParametersResponse fromMap(
          Map<dynamic, dynamic> map) =>
      AndroidEqualizerGetParametersResponse(
        parameters: AndroidEqualizerParametersMessage.fromMap(
            map['parameters'] as Map<dynamic, dynamic>),
      );
}

/// Information communicated to the platform implementation when setting the
/// gain for an equalizer band.
class AndroidEqualizerBandSetGainRequest {
  final int bandIndex;
  final double gain;

  const AndroidEqualizerBandSetGainRequest({
    required this.bandIndex,
    required this.gain,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'bandIndex': bandIndex,
        'gain': gain,
      };
}

/// Information returned by the platform implementation after setting the gain
/// for an equalizer band.
class AndroidEqualizerBandSetGainResponse {
  AndroidEqualizerBandSetGainResponse();

  static AndroidEqualizerBandSetGainResponse fromMap(
          Map<dynamic, dynamic> map) =>
      AndroidEqualizerBandSetGainResponse();
}

/// Information about an audio effect to be communicated with the platform
/// implementation.
abstract class AudioEffectMessage {
  final bool enabled;

  const AudioEffectMessage({required this.enabled});

  Map<dynamic, dynamic> toMap();
}

/// Information about a loudness enhancer to be communicated with the platform
/// implementation.
class AndroidLoudnessEnhancerMessage extends AudioEffectMessage {
  final double targetGain;

  const AndroidLoudnessEnhancerMessage({
    required bool enabled,
    required this.targetGain,
  }) : super(enabled: enabled);

  @override
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': 'AndroidLoudnessEnhancer',
        'enabled': enabled,
        'targetGain': targetGain,
      };
}

/// Information about an equalizer band to be communicated with the platform
/// implementation.
class AndroidEqualizerBandMessage {
  /// A zero-based index of the position of this band within its [AndroidEqualizer].
  final int index;

  /// The lower frequency of this band in hertz.
  final double lowerFrequency;

  /// The upper frequency of this band in hertz.
  final double upperFrequency;

  /// The center frequency of this band in hertz.
  final double centerFrequency;

  /// The gain for this band in decibels.
  final double gain;

  const AndroidEqualizerBandMessage({
    required this.index,
    required this.lowerFrequency,
    required this.upperFrequency,
    required this.centerFrequency,
    required this.gain,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'index': index,
        'lowerFrequency': lowerFrequency,
        'upperFrequency': upperFrequency,
        'centerFrequency': centerFrequency,
        'gain': gain,
      };

  static AndroidEqualizerBandMessage fromMap(Map<dynamic, dynamic> map) =>
      AndroidEqualizerBandMessage(
        index: map['index'] as int,
        lowerFrequency: map['lowerFrequency'] as double,
        upperFrequency: map['upperFrequency'] as double,
        centerFrequency: map['centerFrequency'] as double,
        gain: map['gain'] as double,
      );
}

/// Information about the equalizer parameters to be communicated with the
/// platform implementation.
class AndroidEqualizerParametersMessage {
  final double minDecibels;
  final double maxDecibels;
  final List<AndroidEqualizerBandMessage> bands;

  const AndroidEqualizerParametersMessage({
    required this.minDecibels,
    required this.maxDecibels,
    required this.bands,
  });

  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'minDecibels': minDecibels,
        'maxDecibels': maxDecibels,
        'bands': bands.map((band) => band.toMap()).toList(),
      };

  static AndroidEqualizerParametersMessage fromMap(Map<dynamic, dynamic> map) =>
      AndroidEqualizerParametersMessage(
        minDecibels: map['minDecibels'] as double,
        maxDecibels: map['maxDecibels'] as double,
        bands: (map['bands'] as List<dynamic>)
            .map((dynamic bandMap) => AndroidEqualizerBandMessage.fromMap(
                bandMap as Map<dynamic, dynamic>))
            .toList(),
      );
}

/// Information about the equalizer to be communicated with the platform
/// implementation.
class AndroidEqualizerMessage extends AudioEffectMessage {
  final AndroidEqualizerParametersMessage? parameters;

  const AndroidEqualizerMessage({
    required bool enabled,
    required this.parameters,
  }) : super(enabled: enabled);

  @override
  Map<dynamic, dynamic> toMap() => <dynamic, dynamic>{
        'type': 'AndroidEqualizer',
        'enabled': enabled,
        'parameters': parameters?.toMap(),
      };
}
