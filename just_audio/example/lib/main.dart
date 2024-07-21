// This is a minimal example demonstrating a play/pause button and a seek bar.
// More advanced examples demonstrating other features can be found in the same
// directory as this example in the GitHub repository.

import 'dart:io';

import 'package:audio_session/audio_session.dart';
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:just_audio_example/common.dart';
import 'package:rxdart/rxdart.dart';

void main() => runApp(const MyApp());

class MyApp extends StatefulWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  MyAppState createState() => MyAppState();
}

class MyAppState extends State<MyApp> {
  final _player = AudioPlayer();
  final _player2 = AudioPlayer();

  @override
  void initState() {
    super.initState();
    _init();
    _init2();
  }

  Future<void> _init() async {
    // Inform the operating system of our app's audio attributes etc.
    // We pick a reasonable default for an app that plays speech.
    final session = await AudioSession.instance;
    await session.configure(const AudioSessionConfiguration.music());
    // Listen to errors during playback.
    _player.playbackEventStream.listen((event) {},
        onError: (Object e, StackTrace stackTrace) {
      print('A stream error occurred: $e');
    });

    // Try to load audio from a source and catch any errors.
    try {
      // AAC example: https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.aac
      await _player.setSource(
        AudioVideoSource.file(
            '/storage/emulated/0/Music/video test/5UUO_NmnSFc_130422.m4a'),
        videoOptions: VideoSourceOptions(
          // source: '/storage/emulated/0/Music/video test/5UUO_NmnSFc_480p.mp4',
          source: LockCachingVideoSource(
            Uri.parse(
                'https://sample-videos.com/video321/mp4/720/big_buck_bunny_720p_5mb.mp4'),
            cacheFile:
                File('/storage/emulated/0/Music/big_buck_bunny_cached.mp4'),
          ),
          loop: false,
          videoOnly: false,
        ),
      );
    } catch (e) {
      print("Error loading audio source: $e");
    }
  }

  Future<void> _init2() async {
    // Inform the operating system of our app's audio attributes etc.
    // We pick a reasonable default for an app that plays speech.
    final session = await AudioSession.instance;
    await session.configure(const AudioSessionConfiguration.music());
    // Listen to errors during playback.
    _player2.playbackEventStream.listen((event) {},
        onError: (Object e, StackTrace stackTrace) {
      print('A stream error occurred: $e');
    });

    try {
      await _player2.setSource(
        AudioVideoSource.file('/storage/emulated/0/Music/video test/a2.m4a'),
        videoOptions: VideoSourceOptions(
          source: AudioVideoSource.file(
            '/storage/emulated/0/Music/video test/v2.mp4',
          ),
          loop: false,
          videoOnly: false,
        ),
      );
    } catch (e) {
      print("Error loading audio source: $e");
    }
  }

  @override
  void dispose() {
    _player.dispose();
    _player2.dispose();
    super.dispose();
  }

  /// Collects the data useful for displaying in a seek bar, using a handy
  /// feature of rx_dart to combine the 3 streams of interest into one.
  Stream<PositionData> get _positionDataStream =>
      Rx.combineLatest3<Duration, Duration, Duration?, PositionData>(
          _player.positionStream,
          _player.bufferedPositionStream,
          _player.durationStream,
          (position, bufferedPosition, duration) => PositionData(
              position, bufferedPosition, duration ?? Duration.zero));
  Stream<PositionData> get _positionDataStream2 =>
      Rx.combineLatest3<Duration, Duration, Duration?, PositionData>(
          _player2.positionStream,
          _player2.bufferedPositionStream,
          _player2.durationStream,
          (position, bufferedPosition, duration) => PositionData(
              position, bufferedPosition, duration ?? Duration.zero));

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData.dark(),
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        body: SafeArea(
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.center,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                StreamBuilder<VideoInfoData>(
                  stream: _player.videoInfoStream,
                  builder: (context, snapshot) {
                    final info = snapshot.data;
                    // inspect(info);
                    final id = info?.textureId;
                    if (info == null || id == null || !info.isInitialized) {
                      return const Text('no video');
                    }
                    final aspectRatio = info.height / info.width;
                    final ctxWidth = MediaQuery.of(context).size.width;
                    return SizedBox(
                      width: ctxWidth,
                      height: ctxWidth * aspectRatio,
                      child: Texture(textureId: id),
                    );
                  },
                ),
                // Display play/pause button and volume/speed sliders.
                ControlButtons(_player),
                // Display seek bar. Using StreamBuilder, this widget rebuilds
                // each time the position, buffered position or duration changes.
                StreamBuilder<PositionData>(
                  stream: _positionDataStream,
                  builder: (context, snapshot) {
                    final positionData = snapshot.data;
                    return SeekBar(
                      duration: positionData?.duration ?? Duration.zero,
                      position: positionData?.position ?? Duration.zero,
                      bufferedPosition:
                          positionData?.bufferedPosition ?? Duration.zero,
                      onChangeEnd: _player.seek,
                    );
                  },
                ),

                ElevatedButton(
                  onPressed: () {
                    _player.setVideo(
                      VideoSourceOptions(
                        source: AudioVideoSource.file(
                          '/storage/emulated/0/Music/video test/v2.mp4',
                        ),
                        loop: false,
                        videoOnly: false,
                      ),
                    );
                  },
                  child: const Text('set video 1'),
                ),
                ElevatedButton(
                  onPressed: () {
                    _player.setVideo(
                      VideoSourceOptions(
                        source: AudioVideoSource.file(
                            '/storage/emulated/0/Music/video test/5UUO_NmnSFc_480p.mp4'),
                        loop: false,
                        videoOnly: false,
                      ),
                    );
                  },
                  child: const Text('set video 2'),
                ),
                ElevatedButton(
                  onPressed: () {
                    final vid = VideoSourceOptions(
                      source: LockCachingVideoSource(
                        Uri.parse(
                            'https://sample-videos.com/video321/mp4/720/big_buck_bunny_720p_5mb.mp4'),
                        cacheFile: File(
                            '/storage/emulated/0/Download/big_buck_bunny_cached.mp4'),
                      ),
                      loop: false,
                      videoOnly: false,
                    );
                    _player2.setVideo(vid);
                    // _player2.setSource(
                    //   AudioVideoSource.file(
                    //       '/storage/emulated/0/Music/_uHTZAyYFEg_130506.m4a'),
                    //   videoOptions: vid,
                    // );
                  },
                  child: const Text('set video https'),
                ),
                ElevatedButton(
                  onPressed: () {
                    _player.setVideo(null);
                  },
                  child: const Text('set video none'),
                ),
                // -- video 2
                StreamBuilder<VideoInfoData>(
                  stream: _player2.videoInfoStream,
                  builder: (context, snapshot) {
                    final info = snapshot.data;
                    // inspect(info);
                    print('----------> ${info?.height}');
                    final id = info?.textureId;
                    if (info == null || id == null || id == -1) {
                      return const Text('no video');
                    }
                    final aspectRatio = info.height / info.width;
                    final ctxWidth = MediaQuery.of(context).size.width;
                    return SizedBox(
                      width: ctxWidth,
                      height: ctxWidth * aspectRatio,
                      child: Texture(textureId: id),
                    );
                  },
                ),
                ControlButtons(_player2),
                StreamBuilder<PositionData>(
                  stream: _positionDataStream2,
                  builder: (context, snapshot) {
                    final positionData = snapshot.data;
                    return SeekBar(
                      duration: positionData?.duration ?? Duration.zero,
                      position: positionData?.position ?? Duration.zero,
                      bufferedPosition:
                          positionData?.bufferedPosition ?? Duration.zero,
                      onChangeEnd: _player2.seek,
                    );
                  },
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Displays the play/pause button and volume/speed sliders.
class ControlButtons extends StatelessWidget {
  final AudioPlayer player;

  const ControlButtons(this.player, {Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        // Opens volume slider dialog
        IconButton(
          icon: const Icon(Icons.volume_up),
          onPressed: () {
            showSliderDialog(
              context: context,
              title: "Adjust volume",
              divisions: 10,
              min: 0.0,
              max: 1.0,
              value: player.volume,
              stream: player.volumeStream,
              onChanged: player.setVolume,
            );
          },
        ),

        /// This StreamBuilder rebuilds whenever the player state changes, which
        /// includes the playing/paused state and also the
        /// loading/buffering/ready state. Depending on the state we show the
        /// appropriate button or loading indicator.
        StreamBuilder<PlayerState>(
          stream: player.playerStateStream,
          builder: (context, snapshot) {
            final playerState = snapshot.data;
            final processingState = playerState?.processingState;
            final playing = playerState?.playing;
            if (processingState == ProcessingState.loading ||
                processingState == ProcessingState.buffering) {
              return Container(
                margin: const EdgeInsets.all(8.0),
                width: 64.0,
                height: 64.0,
                child: const CircularProgressIndicator(),
              );
            } else if (playing != true) {
              return IconButton(
                icon: const Icon(Icons.play_arrow),
                iconSize: 64.0,
                onPressed: player.play,
              );
            } else if (processingState != ProcessingState.completed) {
              return IconButton(
                icon: const Icon(Icons.pause),
                iconSize: 64.0,
                onPressed: player.pause,
              );
            } else {
              return IconButton(
                icon: const Icon(Icons.replay),
                iconSize: 64.0,
                onPressed: () => player.seek(Duration.zero),
              );
            }
          },
        ),
        // Opens speed slider dialog
        StreamBuilder<double>(
          stream: player.speedStream,
          builder: (context, snapshot) => IconButton(
            icon: Text("${snapshot.data?.toStringAsFixed(1)}x",
                style: const TextStyle(fontWeight: FontWeight.bold)),
            onPressed: () {
              showSliderDialog(
                context: context,
                title: "Adjust speed",
                divisions: 10,
                min: 0.5,
                max: 1.5,
                value: player.speed,
                stream: player.speedStream,
                onChanged: player.setSpeed,
              );
            },
          ),
        ),
      ],
    );
  }
}
