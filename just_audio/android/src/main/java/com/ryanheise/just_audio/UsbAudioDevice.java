// by claude
package com.ryanheise.just_audio;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// a usb audio dac taken over from the kernel driver: its formats, rates, clock and hardware volume.
final class UsbAudioDevice {

  private static final String TAG = "UsbAudioDevice";
  private static final int REQUEST_TYPE_INTERFACE_OUT = 0x21;
  private static final int REQUEST_TYPE_INTERFACE_IN = 0xA1;
  private static final int REQUEST_TYPE_ENDPOINT_OUT = 0x22;
  private static final int REQUEST_CUR = 0x01;
  private static final int REQUEST_RANGE = 0x02;
  private static final int REQUEST_GET_CUR_UAC1 = 0x81;
  private static final int REQUEST_GET_MIN_UAC1 = 0x82;
  private static final int REQUEST_GET_MAX_UAC1 = 0x83;
  private static final int REQUEST_GET_RES_UAC1 = 0x84;
  private static final int CONTROL_SAMPLING_FREQUENCY = 0x01;
  private static final int CONTROL_CLOCK_VALID = 0x02;
  private static final int CONTROL_MUTE = 0x01;
  private static final int CONTROL_VOLUME = 0x02;
  private static final int TIMEOUT_MS = 500;
  private static final int CLOCK_LOCK_WAIT_MS = 30;

  private static final int[] STANDARD_RATES = {
      8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 64000, //
      88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000,
  };

  final UsbDevice device;
  final String name;
  final boolean isHighSpeed;
  private final UsbDeviceConnection connection;
  private final UsbAudioDescriptors descriptors;
  private final List<UsbInterface> claimedInterfaces;
  private final Map<UsbAudioDescriptors.Format, int[]> ratesByFormat;
  @Nullable private final VolumeRange volumeRange;
  private final int initialVolume;
  private final int initialMute;

  @Nullable private UsbAudioDescriptors.Format activeFormat;
  private int activeRate;
  private int appliedVolume = Integer.MIN_VALUE;
  private boolean appliedMute;
  private float playerGain = 1f;
  private float systemGain = 1f;
  private final Map<UsbAudioStream, Float> softwareGainStreams = new HashMap<>();
  private int openStreams;
  private boolean isClosing;

  private UsbAudioDevice(UsbDevice device, UsbDeviceConnection connection, UsbAudioDescriptors descriptors, List<UsbInterface> claimedInterfaces,
      boolean isHighSpeed) {
    this.device = device;
    this.connection = connection;
    this.descriptors = descriptors;
    this.claimedInterfaces = claimedInterfaces;
    this.isHighSpeed = isHighSpeed;
    this.name = displayNameOf(device);
    this.ratesByFormat = readRates();
    this.initialVolume = readVolumeControl(CONTROL_VOLUME, 2);
    this.initialMute = readVolumeControl(CONTROL_MUTE, 1);
    this.volumeRange = readWritableVolumeRange(initialVolume);
  }

  /// usb string descriptors can carry padding or garbage after the name, it's cut at the first unprintable character.
  static String displayNameOf(UsbDevice device) {
    @Nullable final String productName = device.getProductName();
    if (productName == null) return device.getDeviceName();
    int end = 0;
    while (end < productName.length() && isPrintable(productName.charAt(end))) end++;
    final String name = productName.substring(0, end).trim();
    return name.isEmpty() ? device.getDeviceName() : name;
  }

  private static boolean isPrintable(char c) {
    if (Character.isISOControl(c) || Character.isSurrogate(c)) return false;
    final int type = Character.getType(c);
    return type != Character.UNASSIGNED && type != Character.PRIVATE_USE && c != '\uFFFD';
  }

  static boolean isAudioOutput(UsbDevice device) {
    for (int i = 0; i < device.getInterfaceCount(); i++) {
      final UsbInterface usbInterface = device.getInterface(i);
      if (usbInterface.getInterfaceClass() != UsbConstants.USB_CLASS_AUDIO || usbInterface.getInterfaceSubclass() != 2) continue;
      for (int e = 0; e < usbInterface.getEndpointCount(); e++) {
        final boolean isOut = usbInterface.getEndpoint(e).getDirection() == UsbConstants.USB_DIR_OUT;
        if (isOut && usbInterface.getEndpoint(e).getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) return true;
      }
    }
    return false;
  }

  /// claims the audio interfaces, which detaches the kernel driver, null when the device can't be driven directly.
  @Nullable
  static UsbAudioDevice open(UsbManager manager, UsbDevice device) {
    final UsbDeviceConnection connection = manager.openDevice(device);
    if (connection == null) return null;
    final byte[] raw = connection.getRawDescriptors();
    final UsbAudioDescriptors descriptors = raw == null ? null : UsbAudioDescriptors.parse(raw);
    if (descriptors == null || descriptors.formats.isEmpty()) {
      connection.close();
      return null;
    }
    final List<UsbInterface> claimed = new ArrayList<>();
    for (int i = 0; i < device.getInterfaceCount(); i++) {
      final UsbInterface usbInterface = device.getInterface(i);
      if (usbInterface.getAlternateSetting() != 0 || !isUsedInterface(descriptors, usbInterface.getId())) continue;
      if (!connection.claimInterface(usbInterface, true)) {
        for (UsbInterface claimedInterface : claimed) connection.releaseInterface(claimedInterface);
        connection.close();
        return null;
      }
      claimed.add(usbInterface);
      if (usbInterface.getId() != descriptors.controlInterface) connection.setInterface(usbInterface);
    }
    final boolean isHighSpeed = UsbAudioStream.isHighSpeed(connection.getFileDescriptor());
    final UsbAudioDevice audioDevice = new UsbAudioDevice(device, connection, descriptors, claimed, isHighSpeed);
    if (audioDevice.ratesByFormat.isEmpty()) {
      audioDevice.close();
      return null;
    }
    return audioDevice;
  }

  private static boolean isUsedInterface(UsbAudioDescriptors descriptors, int interfaceNumber) {
    if (interfaceNumber == descriptors.controlInterface) return true;
    for (UsbAudioDescriptors.Format format : descriptors.formats) {
      if (format.interfaceNumber == interfaceNumber) return true;
    }
    return false;
  }

  int getFileDescriptor() {
    return connection.getFileDescriptor();
  }

  boolean hasHardwareVolume() {
    return volumeRange != null;
  }

  /// the encoding [inputEncoding] reaches the dac in without losing a bit, or [C#ENCODING_INVALID].
  @C.PcmEncoding
  int getLosslessEncoding(@C.PcmEncoding int inputEncoding, int sampleRate, int channels) {
    for (int subslotBytes : losslessSubslotsFor(inputEncoding)) {
      final int minResolution = minResolutionFor(inputEncoding);
      if (findFormat(subslotBytes, minResolution, sampleRate, channels) != null) return encodingForSubslot(subslotBytes);
    }
    return C.ENCODING_INVALID;
  }

  @Nullable
  UsbAudioDescriptors.Format findFormatForEncoding(@C.PcmEncoding int encoding, int sampleRate, int channels) {
    return findFormat(bytesPerSample(encoding), 0, sampleRate, channels);
  }

  /// the highest resolution format playing [channels] at [sampleRate], for tracks that can't go through losslessly.
  @Nullable
  UsbAudioDescriptors.Format findBestFormat(int sampleRate, int channels) {
    UsbAudioDescriptors.Format best = null;
    for (UsbAudioDescriptors.Format format : ratesByFormat.keySet()) {
      if (!supports(format, sampleRate) || !fitsChannelsProcessed(format, channels)) continue;
      if (best == null || format.bitResolution > best.bitResolution) best = format;
    }
    return best;
  }

  /// the rate closest to [sampleRate] in its own family (44.1k or 48k), so resampling stays as simple as possible.
  int getTargetSampleRate(int sampleRate, int channels) {
    int best = 0;
    double bestScore = Double.MAX_VALUE;
    for (Map.Entry<UsbAudioDescriptors.Format, int[]> entry : ratesByFormat.entrySet()) {
      if (!fitsChannelsProcessed(entry.getKey(), channels)) continue;
      for (int rate : entry.getValue()) {
        if (rate == sampleRate) return rate;
        final boolean isSameFamily = rate % 11025 == 0 == (sampleRate % 11025 == 0);
        final double score = Math.abs(Math.log((double) rate / sampleRate)) + (isSameFamily ? 0 : 10);
        if (score < bestScore) {
          bestScore = score;
          best = rate;
        }
      }
    }
    return best;
  }

  int getMaxSampleRate() {
    int max = 0;
    for (int[] rates : ratesByFormat.values()) {
      for (int rate : rates) max = Math.max(max, rate);
    }
    return max;
  }

  int getMaxBitDepth() {
    int max = 0;
    for (UsbAudioDescriptors.Format format : ratesByFormat.keySet()) max = Math.max(max, format.bitResolution);
    return max;
  }

  /// switches the interface and the clock only when they changed, so gapless tracks don't reopen the stream.
  synchronized boolean configure(UsbAudioDescriptors.Format format, int sampleRate) {
    if (format == activeFormat && sampleRate == activeRate) return true;
    final UsbAudioDescriptors.Format previous = activeFormat;
    if (previous != null && previous.interfaceNumber != format.interfaceNumber) setAltSetting(previous.interfaceNumber, 0);
    if (!setAltSetting(format.interfaceNumber, 0)) return false;
    setSampleRate(format, sampleRate);
    if (!setAltSetting(format.interfaceNumber, format.altSetting)) return false;
    waitForClock(format);
    activeFormat = format;
    activeRate = sampleRate;
    reapplyVolume();
    return true;
  }

  /// some dacs reset their volume when the interface or clock changes.
  private void reapplyVolume() {
    @Nullable final UsbAudioDescriptors.VolumeControl control = descriptors.volumeControl;
    if (volumeRange == null || control == null || appliedVolume == Integer.MIN_VALUE) return;
    writeVolume(control, appliedVolume);
    if (control.hasMute) writeMute(control, appliedMute);
  }

  /// the player's volume for [stream], linear. it lands in the dac's own volume when it has one, so the samples stay untouched,
  /// otherwise it scales [stream]'s float input before dithering ([isFloat]), and integer streams stay fixed.
  synchronized void setVolume(UsbAudioStream stream, float gain, boolean isFloat) {
    final float effectiveGain = gain * systemGain;
    if (volumeRange != null) {
      playerGain = gain;
      writeHardwareGain(effectiveGain);
    } else if (isFloat) {
      softwareGainStreams.put(stream, gain);
      stream.setGain(effectiveGain);
    }
  }

  /// android's media volume, linear, so the dac follows the volume keys like any other output.
  synchronized void setSystemGain(float gain) {
    if (gain == systemGain) return;
    systemGain = gain;
    if (volumeRange != null) {
      writeHardwareGain(playerGain * gain);
      return;
    }
    for (Map.Entry<UsbAudioStream, Float> entry : softwareGainStreams.entrySet()) {
      final UsbAudioStream stream = entry.getKey();
      final float streamPlayerGain = entry.getValue();
      stream.setGain(streamPlayerGain * gain);
    }
  }

  private void writeHardwareGain(float gain) {
    final VolumeRange range = volumeRange;
    final UsbAudioDescriptors.VolumeControl control = descriptors.volumeControl;
    final double db = gain <= 0f ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(gain);
    final boolean mute = db * 256.0 <= range.min;
    int value = mute ? range.min : (int) Math.round(db * 256.0);
    if (range.resolution > 0) value = range.min + Math.round((value - range.min) / (float) range.resolution) * range.resolution;
    value = Math.max(range.min, Math.min(range.max, value));
    if (value != appliedVolume) {
      appliedVolume = value;
      if (!writeVolume(control, value)) Log.w(TAG, "volume write failed, value=" + value);
    }
    if (control.hasMute && mute != appliedMute) {
      appliedMute = mute;
      writeMute(control, mute);
    }
  }

  /// false once the device is closing, a stream must never outlive the connection it streams on.
  synchronized boolean acquireStream() {
    if (isClosing) return false;
    openStreams++;
    return true;
  }

  synchronized boolean hasOpenStreams() {
    return openStreams > 0;
  }

  synchronized void releaseStream(@Nullable UsbAudioStream stream) {
    if (stream != null) softwareGainStreams.remove(stream);
    openStreams--;
    if (isClosing && openStreams == 0) closeNow();
  }

  /// closes once the last stream on it is released.
  synchronized void close() {
    isClosing = true;
    if (openStreams == 0) closeNow();
  }

  /// releasing an interface lets the kernel driver rebind it, handing the dac back to android.
  private void closeNow() {
    final UsbAudioDescriptors.VolumeControl control = descriptors.volumeControl;
    if (control != null && appliedVolume != Integer.MIN_VALUE) {
      if (initialVolume != Integer.MIN_VALUE) writeVolume(control, initialVolume);
      if (control.hasMute && initialMute != Integer.MIN_VALUE) writeMute(control, initialMute != 0);
    }
    for (UsbInterface usbInterface : claimedInterfaces) {
      if (usbInterface.getId() != descriptors.controlInterface) connection.setInterface(usbInterface);
      connection.releaseInterface(usbInterface);
    }
    connection.close();
    activeFormat = null;
  }

  private boolean writeVolume(UsbAudioDescriptors.VolumeControl control, int value) {
    final byte[] data = {(byte) value, (byte) (value >> 8)};
    final int wIndex = (control.unitId << 8) | descriptors.controlInterface;
    boolean didWriteAll = true;
    for (int channel : control.channels) {
      final int wValue = (CONTROL_VOLUME << 8) | channel;
      final int written = connection.controlTransfer(REQUEST_TYPE_INTERFACE_OUT, REQUEST_CUR, wValue, wIndex, data, 2, TIMEOUT_MS);
      if (written < 0) didWriteAll = false;
    }
    return didWriteAll;
  }

  private void writeMute(UsbAudioDescriptors.VolumeControl control, boolean mute) {
    final byte[] data = {(byte) (mute ? 1 : 0)};
    final int wIndex = (control.unitId << 8) | descriptors.controlInterface;
    connection.controlTransfer(REQUEST_TYPE_INTERFACE_OUT, REQUEST_CUR, CONTROL_MUTE << 8, wIndex, data, 1, TIMEOUT_MS);
  }

  /// Integer.MIN_VALUE when the dac has no such control or doesn't answer.
  private int readVolumeControl(int controlSelector, int length) {
    @Nullable final UsbAudioDescriptors.VolumeControl control = descriptors.volumeControl;
    if (control == null) return Integer.MIN_VALUE;
    final int channel = controlSelector == CONTROL_MUTE ? 0 : control.channels[0];
    final int request = descriptors.uacVersion == 2 ? REQUEST_CUR : REQUEST_GET_CUR_UAC1;
    final byte[] data = new byte[2];
    final int wValue = (controlSelector << 8) | channel;
    final int wIndex = (control.unitId << 8) | descriptors.controlInterface;
    final int read = connection.controlTransfer(REQUEST_TYPE_INTERFACE_IN, request, wValue, wIndex, data, length, TIMEOUT_MS);
    if (read < length) return Integer.MIN_VALUE;
    return length == 2 ? readSigned16(data, 0) : data[0];
  }

  private boolean setAltSetting(int interfaceNumber, int altSetting) {
    for (int i = 0; i < device.getInterfaceCount(); i++) {
      final UsbInterface usbInterface = device.getInterface(i);
      if (usbInterface.getId() == interfaceNumber && usbInterface.getAlternateSetting() == altSetting) {
        return connection.setInterface(usbInterface);
      }
    }
    return false;
  }

  private void setSampleRate(UsbAudioDescriptors.Format format, int sampleRate) {
    if (descriptors.uacVersion == 2) {
      if (format.clockSourceId < 0) return;
      final byte[] data = {(byte) sampleRate, (byte) (sampleRate >> 8), (byte) (sampleRate >> 16), (byte) (sampleRate >> 24)};
      final int wIndex = (format.clockSourceId << 8) | descriptors.controlInterface;
      connection.controlTransfer(REQUEST_TYPE_INTERFACE_OUT, REQUEST_CUR, CONTROL_SAMPLING_FREQUENCY << 8, wIndex, data, 4, TIMEOUT_MS);
    } else {
      final boolean hasSeveralRates = format.discreteRates.length != 1;
      if (!format.hasSampleRateControl && !hasSeveralRates) return;
      final byte[] data = {(byte) sampleRate, (byte) (sampleRate >> 8), (byte) (sampleRate >> 16)};
      connection.controlTransfer(REQUEST_TYPE_ENDPOINT_OUT, REQUEST_CUR, CONTROL_SAMPLING_FREQUENCY << 8, format.endpoint, data, 3, TIMEOUT_MS);
    }
  }

  private void waitForClock(UsbAudioDescriptors.Format format) {
    if (descriptors.uacVersion != 2 || format.clockSourceId < 0) {
      SystemClock.sleep(CLOCK_LOCK_WAIT_MS);
      return;
    }
    final byte[] data = new byte[1];
    final int wIndex = (format.clockSourceId << 8) | descriptors.controlInterface;
    for (int attempt = 0; attempt < 10; attempt++) {
      final int read = connection.controlTransfer(REQUEST_TYPE_INTERFACE_IN, REQUEST_CUR, CONTROL_CLOCK_VALID << 8, wIndex, data, 1, TIMEOUT_MS);
      if (read < 1 || (data[0] & 0x1) != 0) return;
      SystemClock.sleep(10);
    }
  }

  private Map<UsbAudioDescriptors.Format, int[]> readRates() {
    final Map<UsbAudioDescriptors.Format, int[]> rates = new HashMap<>();
    final Map<Integer, int[]> clockRates = new HashMap<>();
    for (UsbAudioDescriptors.Format format : descriptors.formats) {
      final int[] formatRates;
      if (descriptors.uacVersion == 2) {
        int[] fromClock = clockRates.get(format.clockSourceId);
        if (fromClock == null) {
          fromClock = readClockRates(format.clockSourceId);
          clockRates.put(format.clockSourceId, fromClock);
        }
        formatRates = fromClock;
      } else if (format.discreteRates.length > 0) {
        formatRates = format.discreteRates;
      } else {
        formatRates = standardRatesWithin(new long[] {format.minRate, format.maxRate, 1});
      }
      if (formatRates.length > 0) rates.put(format, formatRates);
    }
    return rates;
  }

  /// a uac2 RANGE reply: count, then {min, max, resolution} per subrange.
  private int[] readClockRates(int clockSourceId) {
    if (clockSourceId < 0) return new int[0];
    final int wIndex = (clockSourceId << 8) | descriptors.controlInterface;
    final int wValue = CONTROL_SAMPLING_FREQUENCY << 8;
    final byte[] header = new byte[2];
    if (connection.controlTransfer(REQUEST_TYPE_INTERFACE_IN, REQUEST_RANGE, wValue, wIndex, header, 2, TIMEOUT_MS) < 2) return new int[0];
    final int count = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8);
    if (count <= 0) return new int[0];
    final byte[] reply = new byte[2 + count * 12];
    final int read = connection.controlTransfer(REQUEST_TYPE_INTERFACE_IN, REQUEST_RANGE, wValue, wIndex, reply, reply.length, TIMEOUT_MS);
    final int available = Math.max(0, (read - 2) / 12);
    final long[] ranges = new long[available * 3];
    for (int r = 0; r < available; r++) {
      for (int field = 0; field < 3; field++) {
        ranges[r * 3 + field] = readUnsigned32(reply, 2 + r * 12 + field * 4);
      }
    }
    return standardRatesWithin(ranges);
  }

  /// [ranges] holds {min, max, resolution} triplets, a zero resolution is continuous.
  private static int[] standardRatesWithin(long[] ranges) {
    final List<Integer> rates = new ArrayList<>();
    for (int rate : STANDARD_RATES) {
      for (int r = 0; r + 2 < ranges.length; r += 3) {
        final long min = ranges[r];
        final long max = ranges[r + 1];
        final long resolution = ranges[r + 2];
        if (rate < min || rate > max) continue;
        final boolean isOnGrid = resolution == 0 || (rate - min) % resolution == 0;
        if (isOnGrid) {
          rates.add(rate);
          break;
        }
      }
    }
    final int[] array = new int[rates.size()];
    for (int i = 0; i < array.length; i++) array[i] = rates.get(i);
    return array;
  }

  /// a volume the dac reports but doesn't take would leave bit-perfect playback stuck at full volume, so it's written and read back
  /// once, at both ends of its range, before anything plays.
  @Nullable
  private VolumeRange readWritableVolumeRange(int currentVolume) {
    @Nullable final VolumeRange range = readVolumeRange();
    @Nullable final UsbAudioDescriptors.VolumeControl control = descriptors.volumeControl;
    if (range == null || control == null || currentVolume == Integer.MIN_VALUE) return null;
    final int probeVolume = currentVolume == range.min ? range.max : range.min;
    final boolean didWrite = writeVolume(control, probeVolume);
    final int readBack = readVolumeControl(CONTROL_VOLUME, 2);
    writeVolume(control, currentVolume);
    final boolean isWritable = didWrite && readBack == probeVolume;
    if (!isWritable) Log.w(TAG, "volume control isn't writable, wrote=" + probeVolume + " read=" + readBack);
    return isWritable ? range : null;
  }

  @Nullable
  private VolumeRange readVolumeRange() {
    final UsbAudioDescriptors.VolumeControl control = descriptors.volumeControl;
    if (control == null) return null;
    final int channel = control.channels[0];
    final int wValue = (CONTROL_VOLUME << 8) | channel;
    final int wIndex = (control.unitId << 8) | descriptors.controlInterface;
    if (descriptors.uacVersion == 2) {
      final byte[] reply = new byte[8];
      final int read = connection.controlTransfer(REQUEST_TYPE_INTERFACE_IN, REQUEST_RANGE, wValue, wIndex, reply, reply.length, TIMEOUT_MS);
      if (read < 8) return null;
      return VolumeRange.of(readSigned16(reply, 2), readSigned16(reply, 4), readSigned16(reply, 6));
    }
    final byte[] min = new byte[2];
    final byte[] max = new byte[2];
    final byte[] resolution = new byte[2];
    if (connection.controlTransfer(REQUEST_TYPE_INTERFACE_IN, REQUEST_GET_MIN_UAC1, wValue, wIndex, min, 2, TIMEOUT_MS) < 2) return null;
    if (connection.controlTransfer(REQUEST_TYPE_INTERFACE_IN, REQUEST_GET_MAX_UAC1, wValue, wIndex, max, 2, TIMEOUT_MS) < 2) return null;
    final int resolutionRead = connection.controlTransfer(REQUEST_TYPE_INTERFACE_IN, REQUEST_GET_RES_UAC1, wValue, wIndex, resolution, 2, TIMEOUT_MS);
    return VolumeRange.of(readSigned16(min, 0), readSigned16(max, 0), resolutionRead < 2 ? 0 : readSigned16(resolution, 0));
  }

  @Nullable
  private UsbAudioDescriptors.Format findFormat(int subslotBytes, int minResolution, int sampleRate, int channels) {
    UsbAudioDescriptors.Format best = null;
    for (UsbAudioDescriptors.Format format : ratesByFormat.keySet()) {
      if (format.subslotBytes != subslotBytes || format.bitResolution < minResolution) continue;
      if (!supports(format, sampleRate) || !fitsChannels(format, channels)) continue;
      if (best == null || format.bitResolution > best.bitResolution) best = format;
    }
    return best;
  }

  private boolean supports(UsbAudioDescriptors.Format format, int sampleRate) {
    final int[] rates = ratesByFormat.get(format);
    if (rates == null) return false;
    for (int rate : rates) {
      if (rate == sampleRate) return true;
    }
    return false;
  }

  /// mono is duplicated on stereo dacs, which keeps it lossless.
  private static boolean fitsChannels(UsbAudioDescriptors.Format format, int channels) {
    return format.channels == channels || (channels == 1 && format.channels == 2);
  }

  /// processed audio can also be folded down to stereo.
  private static boolean fitsChannelsProcessed(UsbAudioDescriptors.Format format, int channels) {
    return format.channels == channels || format.channels == 2;
  }

  private static int[] losslessSubslotsFor(@C.PcmEncoding int encoding) {
    switch (encoding) {
      case C.ENCODING_PCM_16BIT:
        return new int[] {2, 3, 4};
      case C.ENCODING_PCM_24BIT:
        return new int[] {3, 4};
      case C.ENCODING_PCM_32BIT:
        return new int[] {4};
      case C.ENCODING_PCM_FLOAT:
        return new int[] {4, 3};
      default:
        return new int[0];
    }
  }

  private static int minResolutionFor(@C.PcmEncoding int encoding) {
    switch (encoding) {
      case C.ENCODING_PCM_16BIT:
        return 16;
      case C.ENCODING_PCM_32BIT:
        return 32;
      default:
        return 24;
    }
  }

  static int bytesPerSample(@C.PcmEncoding int encoding) {
    switch (encoding) {
      case C.ENCODING_PCM_16BIT:
        return 2;
      case C.ENCODING_PCM_24BIT:
        return 3;
      default:
        return 4;
    }
  }

  @C.PcmEncoding
  private static int encodingForSubslot(int subslotBytes) {
    switch (subslotBytes) {
      case 2:
        return C.ENCODING_PCM_16BIT;
      case 3:
        return C.ENCODING_PCM_24BIT;
      default:
        return C.ENCODING_PCM_32BIT;
    }
  }

  private static long readUnsigned32(byte[] data, int offset) {
    return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24);
  }

  private static int readSigned16(byte[] data, int offset) {
    return (short) ((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
  }

  /// 1/256 dB units.
  private static final class VolumeRange {
    final int min;
    final int max;
    final int resolution;

    private VolumeRange(int min, int max, int resolution) {
      this.min = min;
      this.max = max;
      this.resolution = resolution;
    }

    @Nullable
    static VolumeRange of(int min, int max, int resolution) {
      return min < max ? new VolumeRange(min, max, Math.max(0, resolution)) : null;
    }
  }
}
