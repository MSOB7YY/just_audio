// by claude
package com.ryanheise.just_audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// USB Audio Class 1 and 2 playback description of a device, parsed from its raw configuration descriptors.
final class UsbAudioDescriptors {

  static final int SYNC_NONE = 0;
  static final int SYNC_ASYNC = 1;
  static final int SYNC_ADAPTIVE = 2;
  static final int SYNC_SYNC = 3;

  private static final int DESCRIPTOR_INTERFACE = 0x04;
  private static final int DESCRIPTOR_ENDPOINT = 0x05;
  private static final int DESCRIPTOR_CS_INTERFACE = 0x24;
  private static final int DESCRIPTOR_CS_ENDPOINT = 0x25;

  private static final int AUDIO_CLASS = 0x01;
  private static final int SUBCLASS_CONTROL = 0x01;
  private static final int SUBCLASS_STREAMING = 0x02;
  private static final int PROTOCOL_UAC2 = 0x20;

  private static final int AC_HEADER = 0x01;
  private static final int AC_INPUT_TERMINAL = 0x02;
  private static final int AC_OUTPUT_TERMINAL = 0x03;
  private static final int AC_MIXER_UNIT = 0x04;
  private static final int AC_SELECTOR_UNIT = 0x05;
  private static final int AC_FEATURE_UNIT = 0x06;
  private static final int AC1_PROCESSING_UNIT = 0x07;
  private static final int AC1_EXTENSION_UNIT = 0x08;
  private static final int AC2_EFFECT_UNIT = 0x07;
  private static final int AC2_PROCESSING_UNIT = 0x08;
  private static final int AC2_EXTENSION_UNIT = 0x09;
  private static final int AC2_CLOCK_SOURCE = 0x0A;
  private static final int AC2_CLOCK_SELECTOR = 0x0B;
  private static final int AC2_CLOCK_MULTIPLIER = 0x0C;

  private static final int AS_GENERAL = 0x01;
  private static final int AS_FORMAT_TYPE = 0x02;
  private static final int EP_GENERAL = 0x01;

  private static final int TERMINAL_USB_STREAMING = 0x0101;

  final int uacVersion;
  final int controlInterface;
  final List<Format> formats;
  /// null when no feature unit on the playback path has a volume control.
  final VolumeControl volumeControl;

  private UsbAudioDescriptors(int uacVersion, int controlInterface, List<Format> formats, VolumeControl volumeControl) {
    this.uacVersion = uacVersion;
    this.controlInterface = controlInterface;
    this.formats = formats;
    this.volumeControl = volumeControl;
  }

  static UsbAudioDescriptors parse(byte[] raw) {
    final Parser parser = new Parser(raw);
    parser.run();
    return parser.build();
  }

  static final class Format {
    final int interfaceNumber;
    final int altSetting;
    final int channels;
    final int subslotBytes;
    final int bitResolution;
    /// uac1 discrete rates, empty for continuous ranges and for uac2 (queried from the clock source).
    final int[] discreteRates;
    final int minRate;
    final int maxRate;
    final int endpoint;
    final int maxPacketBytes;
    final int interval;
    final int syncType;
    final int feedbackEndpoint;
    final int feedbackInterval;
    final boolean hasSampleRateControl;
    final int clockSourceId;

    Format(int interfaceNumber, int altSetting, int channels, int subslotBytes, int bitResolution, int[] discreteRates, int minRate,
        int maxRate, int endpoint, int maxPacketBytes, int interval, int syncType, int feedbackEndpoint, int feedbackInterval,
        boolean hasSampleRateControl, int clockSourceId) {
      this.interfaceNumber = interfaceNumber;
      this.altSetting = altSetting;
      this.channels = channels;
      this.subslotBytes = subslotBytes;
      this.bitResolution = bitResolution;
      this.discreteRates = discreteRates;
      this.minRate = minRate;
      this.maxRate = maxRate;
      this.endpoint = endpoint;
      this.maxPacketBytes = maxPacketBytes;
      this.interval = interval;
      this.syncType = syncType;
      this.feedbackEndpoint = feedbackEndpoint;
      this.feedbackInterval = feedbackInterval;
      this.hasSampleRateControl = hasSampleRateControl;
      this.clockSourceId = clockSourceId;
    }
  }

  static final class VolumeControl {
    final int unitId;
    /// 0 is the master channel, otherwise every logical channel with its own volume.
    final int[] channels;
    final boolean hasMute;

    VolumeControl(int unitId, int[] channels, boolean hasMute) {
      this.unitId = unitId;
      this.channels = channels;
      this.hasMute = hasMute;
    }
  }

  private static final class Parser {
    private final byte[] raw;

    private int uacVersion;
    private int controlInterface = -1;
    private final Map<Integer, int[]> unitSources = new HashMap<>();
    private final Map<Integer, VolumeControl> featureUnits = new HashMap<>();
    private final Map<Integer, Integer> terminalClocks = new HashMap<>();
    private final Map<Integer, int[]> clockSources = new HashMap<>();
    private final List<Integer> clockSourceIds = new ArrayList<>();
    private final List<Integer> outputTerminals = new ArrayList<>();
    private final List<Integer> streamingInputTerminals = new ArrayList<>();
    private final List<Pending> pendings = new ArrayList<>();

    private int interfaceNumber = -1;
    private int altSetting;
    private int interfaceSubclass;
    private boolean isAudioInterface;
    private Pending current;

    Parser(byte[] raw) {
      this.raw = raw;
    }

    void run() {
      int i = 0;
      while (i + 1 < raw.length) {
        final int length = u8(i);
        if (length < 2 || i + length > raw.length) break;
        final int type = u8(i + 1);
        if (type == DESCRIPTOR_INTERFACE && length >= 9) {
          onInterface(i);
        } else if (isAudioInterface && type == DESCRIPTOR_CS_INTERFACE && length >= 3) {
          if (interfaceSubclass == SUBCLASS_CONTROL) {
            onControlDescriptor(i, length);
          } else if (interfaceSubclass == SUBCLASS_STREAMING && current != null) {
            onStreamingDescriptor(i, length);
          }
        } else if (isAudioInterface && interfaceSubclass == SUBCLASS_STREAMING && current != null && type == DESCRIPTOR_ENDPOINT && length >= 7) {
          onEndpoint(i, length);
        } else if (current != null && type == DESCRIPTOR_CS_ENDPOINT && length >= 4 && u8(i + 2) == EP_GENERAL) {
          current.hasSampleRateControl = uacVersion == 1 && (u8(i + 3) & 0x01) != 0;
        }
        i += length;
      }
      commitCurrent();
    }

    private void onInterface(int i) {
      commitCurrent();
      interfaceNumber = u8(i + 2);
      altSetting = u8(i + 3);
      final int interfaceClass = u8(i + 5);
      interfaceSubclass = u8(i + 6);
      final int protocol = u8(i + 7);
      isAudioInterface = interfaceClass == AUDIO_CLASS;
      if (!isAudioInterface) return;
      if (interfaceSubclass == SUBCLASS_CONTROL && controlInterface < 0) {
        controlInterface = interfaceNumber;
        uacVersion = protocol == PROTOCOL_UAC2 ? 2 : 1;
      } else if (interfaceSubclass == SUBCLASS_STREAMING && altSetting > 0) {
        current = new Pending(interfaceNumber, altSetting);
      }
    }

    private void onControlDescriptor(int i, int length) {
      final int subtype = u8(i + 2);
      final boolean isUac2 = uacVersion == 2;
      switch (subtype) {
        case AC_HEADER:
          break;
        case AC_INPUT_TERMINAL: {
          final int id = u8(i + 3);
          final int terminalType = u16(i + 4);
          if (terminalType == TERMINAL_USB_STREAMING) streamingInputTerminals.add(id);
          if (isUac2 && length >= 8) terminalClocks.put(id, u8(i + 7));
          break;
        }
        case AC_OUTPUT_TERMINAL: {
          final int id = u8(i + 3);
          final int terminalType = u16(i + 4);
          if (length >= 8) unitSources.put(id, new int[] {u8(i + 7)});
          if (terminalType != TERMINAL_USB_STREAMING) outputTerminals.add(id);
          break;
        }
        case AC_MIXER_UNIT:
        case AC_SELECTOR_UNIT:
          unitSources.put(u8(i + 3), readPins(i, 4, length));
          break;
        case AC_FEATURE_UNIT:
          onFeatureUnit(i, length);
          break;
        default:
          if (isUac2) onUac2Descriptor(i, length, subtype);
          else onUac1Descriptor(i, length, subtype);
          break;
      }
    }

    private void onUac1Descriptor(int i, int length, int subtype) {
      if (subtype == AC1_PROCESSING_UNIT || subtype == AC1_EXTENSION_UNIT) {
        unitSources.put(u8(i + 3), readPins(i, 6, length));
      }
    }

    private void onUac2Descriptor(int i, int length, int subtype) {
      switch (subtype) {
        case AC2_EFFECT_UNIT:
          if (length >= 7) unitSources.put(u8(i + 3), new int[] {u8(i + 6)});
          break;
        case AC2_PROCESSING_UNIT:
        case AC2_EXTENSION_UNIT:
          unitSources.put(u8(i + 3), readPins(i, 6, length));
          break;
        case AC2_CLOCK_SOURCE:
          clockSourceIds.add(u8(i + 3));
          break;
        case AC2_CLOCK_SELECTOR:
          clockSources.put(u8(i + 3), readPins(i, 4, length));
          break;
        case AC2_CLOCK_MULTIPLIER:
          if (length >= 5) clockSources.put(u8(i + 3), new int[] {u8(i + 4)});
          break;
        default:
          break;
      }
    }

    /// {count, ids...} at [countOffset] inside the descriptor starting at [start].
    private int[] readPins(int start, int countOffset, int length) {
      final int count = u8(start + countOffset);
      final int available = Math.max(0, Math.min(count, length - countOffset - 1));
      final int[] pins = new int[available];
      for (int p = 0; p < available; p++) {
        pins[p] = u8(start + countOffset + 1 + p);
      }
      return pins;
    }

    private void onFeatureUnit(int i, int length) {
      final int id = u8(i + 3);
      unitSources.put(id, new int[] {u8(i + 4)});
      final int controlSize;
      final int firstControl;
      if (uacVersion == 2) {
        controlSize = 4;
        firstControl = i + 5;
      } else {
        controlSize = u8(i + 5);
        firstControl = i + 6;
      }
      if (controlSize <= 0) return;
      final int channelCount = (length - (firstControl - i) - 1) / controlSize;
      final List<Integer> volumeChannels = new ArrayList<>();
      boolean hasMute = false;
      for (int ch = 0; ch < channelCount; ch++) {
        final long controls = readLittleEndian(firstControl + ch * controlSize, controlSize);
        final boolean volume;
        final boolean mute;
        if (uacVersion == 2) {
          volume = ((controls >> 2) & 0x3) == 0x3;
          mute = (controls & 0x3) == 0x3;
        } else {
          volume = (controls & 0x2) != 0;
          mute = (controls & 0x1) != 0;
        }
        if (ch == 0 && mute) hasMute = true;
        if (volume) volumeChannels.add(ch);
      }
      if (volumeChannels.isEmpty()) return;
      final int[] channels = volumeChannels.contains(0) ? new int[] {0} : toArray(volumeChannels);
      featureUnits.put(id, new VolumeControl(id, channels, hasMute));
    }

    private void onStreamingDescriptor(int i, int length) {
      final int subtype = u8(i + 2);
      if (subtype == AS_GENERAL) {
        current.terminalLink = u8(i + 3);
        if (uacVersion == 2) {
          current.isPcm = length >= 10 && (u8(i + 6) & 0x01) != 0;
          if (length >= 11) current.channels = u8(i + 10);
        } else {
          current.isPcm = length >= 7 && u16(i + 5) == 0x0001;
        }
      } else if (subtype == AS_FORMAT_TYPE && length >= 6 && u8(i + 3) == 1) {
        if (uacVersion == 2) {
          current.subslotBytes = u8(i + 4);
          current.bitResolution = u8(i + 5);
        } else if (length >= 8) {
          current.channels = u8(i + 4);
          current.subslotBytes = u8(i + 5);
          current.bitResolution = u8(i + 6);
          final int rateCount = u8(i + 7);
          if (rateCount == 0 && length >= 14) {
            current.minRate = u24(i + 8);
            current.maxRate = u24(i + 11);
          } else {
            final int available = Math.min(rateCount, (length - 8) / 3);
            current.discreteRates = new int[available];
            for (int r = 0; r < available; r++) {
              current.discreteRates[r] = u24(i + 8 + r * 3);
            }
          }
        }
      }
    }

    private void onEndpoint(int i, int length) {
      final int address = u8(i + 2);
      final int attributes = u8(i + 3);
      if ((attributes & 0x3) != 0x1) return;
      final int packed = u16(i + 4);
      final int maxPacketBytes = (packed & 0x7FF) * (((packed >> 11) & 0x3) + 1);
      final int interval = u8(i + 6);
      final boolean isIn = (address & 0x80) != 0;
      final int usage = (attributes >> 4) & 0x3;
      if (!isIn) {
        if (current.endpoint >= 0) return;
        current.endpoint = address;
        current.maxPacketBytes = maxPacketBytes;
        current.interval = interval;
        current.syncType = (attributes >> 2) & 0x3;
        final int synchAddress = length >= 9 ? u8(i + 8) : 0;
        if (synchAddress != 0) current.feedbackEndpoint = synchAddress;
      } else if (usage == 0x1 || address == current.feedbackEndpoint) {
        current.feedbackEndpoint = address;
        current.feedbackInterval = uacVersion == 1 && length >= 8 ? u8(i + 7) : interval;
      }
    }

    private void commitCurrent() {
      final Pending pending = current;
      current = null;
      if (pending == null || pending.endpoint < 0 || !pending.isPcm) return;
      if (pending.subslotBytes < 2 || pending.subslotBytes > 4 || pending.channels <= 0) return;
      pendings.add(pending);
    }

    UsbAudioDescriptors build() {
      final List<Format> formats = new ArrayList<>();
      for (Pending p : pendings) {
        final int clock = uacVersion == 2 ? resolveClockSource(terminalClocks.containsKey(p.terminalLink) ? terminalClocks.get(p.terminalLink) : -1, 0) : -1;
        formats.add(new Format(p.interfaceNumber, p.altSetting, p.channels, p.subslotBytes, p.bitResolution, p.discreteRates, p.minRate,
            p.maxRate, p.endpoint, p.maxPacketBytes, p.interval, p.syncType, p.feedbackEndpoint, p.feedbackInterval,
            p.hasSampleRateControl, clock));
      }
      return new UsbAudioDescriptors(uacVersion, controlInterface, formats, findVolumeControl());
    }

    private int resolveClockSource(int id, int depth) {
      if (id < 0 || depth > 8) return clockSourceIds.isEmpty() ? -1 : clockSourceIds.get(0);
      if (clockSourceIds.contains(id)) return id;
      final int[] sources = clockSources.get(id);
      if (sources == null || sources.length == 0) return clockSourceIds.isEmpty() ? -1 : clockSourceIds.get(0);
      return resolveClockSource(sources[0], depth + 1);
    }

    /// the first feature unit with a volume control on a path from a usb streaming input to an output terminal.
    private VolumeControl findVolumeControl() {
      for (int output : outputTerminals) {
        final List<Integer> path = new ArrayList<>();
        if (!findPath(output, path, 0)) continue;
        for (int unit : path) {
          final VolumeControl control = featureUnits.get(unit);
          if (control != null) return control;
        }
      }
      return null;
    }

    private boolean findPath(int id, List<Integer> path, int depth) {
      if (depth > 16) return false;
      if (streamingInputTerminals.contains(id)) return true;
      final int[] sources = unitSources.get(id);
      if (sources == null) return false;
      path.add(id);
      for (int source : sources) {
        if (findPath(source, path, depth + 1)) return true;
      }
      path.remove(path.size() - 1);
      return false;
    }

    private long readLittleEndian(int offset, int size) {
      long value = 0;
      for (int b = 0; b < size; b++) {
        value |= ((long) u8(offset + b)) << (8 * b);
      }
      return value;
    }

    private int u8(int offset) {
      return raw[offset] & 0xFF;
    }

    private int u16(int offset) {
      return u8(offset) | (u8(offset + 1) << 8);
    }

    private int u24(int offset) {
      return u8(offset) | (u8(offset + 1) << 8) | (u8(offset + 2) << 16);
    }

    private static int[] toArray(List<Integer> list) {
      final int[] array = new int[list.size()];
      for (int i = 0; i < array.length; i++) {
        array[i] = list.get(i);
      }
      return array;
    }
  }

  private static final class Pending {
    final int interfaceNumber;
    final int altSetting;
    int terminalLink = -1;
    boolean isPcm = true;
    int channels;
    int subslotBytes;
    int bitResolution;
    int[] discreteRates = new int[0];
    int minRate;
    int maxRate;
    int endpoint = -1;
    int maxPacketBytes;
    int interval;
    int syncType;
    int feedbackEndpoint = -1;
    int feedbackInterval;
    boolean hasSampleRateControl;

    Pending(int interfaceNumber, int altSetting) {
      this.interfaceNumber = interfaceNumber;
      this.altSetting = altSetting;
    }
  }
}
