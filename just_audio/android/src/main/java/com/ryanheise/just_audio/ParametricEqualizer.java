// by claude
package com.ryanheise.just_audio;

import java.util.List;
import java.util.Map;

/// process-wide, every player's processor reads the latest one.
final class ParametricEqualizer {

  static final int TYPE_PEAK = 0;
  static final int TYPE_LOW_SHELF = 1;
  static final int TYPE_HIGH_SHELF = 2;
  static final int TYPE_LOW_PASS = 3;
  static final int TYPE_HIGH_PASS = 4;
  static final int TYPE_BAND_PASS = 5;
  static final int TYPE_NOTCH = 6;
  static final int TYPE_ALL_PASS = 7;

  static final int CHANNEL_ALL = 0;
  static final int CHANNEL_LEFT = 1;
  static final int CHANNEL_RIGHT = 2;

  private static volatile Config config = Config.DISABLED;

  /// every channel carries their average, the same processor applies it so it costs no extra pass.
  private static volatile boolean isMono;

  private ParametricEqualizer() {}

  static Config get() {
    return config;
  }

  static void set(Map<?, ?> map) {
    config = Config.fromMap(map);
  }

  static boolean isMono() {
    return isMono;
  }

  static void setMono(boolean mono) {
    isMono = mono;
  }

  static final class Config {
    static final Config DISABLED = new Config(false, 0, false, new Band[0]);

    final boolean enabled;
    final double preampDb;
    final boolean limiter;
    final Band[] bands;

    private Config(boolean enabled, double preampDb, boolean limiter, Band[] bands) {
      this.enabled = enabled;
      this.preampDb = preampDb;
      this.limiter = limiter;
      this.bands = bands;
    }

    static Config fromMap(Map<?, ?> map) {
      final List<?> rawBands = (List<?>) map.get("bands");
      final Band[] bands = new Band[rawBands.size()];
      for (int i = 0; i < bands.length; i++) {
        bands[i] = Band.fromMap((Map<?, ?>) rawBands.get(i));
      }
      return new Config(
          (Boolean) map.get("enabled"),
          ((Number) map.get("preamp")).doubleValue(),
          (Boolean) map.get("limiter"),
          bands);
    }
  }

  static final class Band {
    /// stable across edits so a processor keeps the filter state of a band being tweaked.
    final int id;
    final int type;
    final double frequency;
    final double gainDb;
    final double q;
    /// filter order for low/high pass (2, 4, 6, 8), 2 for every other type.
    final int order;
    final int channel;

    private Band(int id, int type, double frequency, double gainDb, double q, int order, int channel) {
      this.id = id;
      this.type = type;
      this.frequency = frequency;
      this.gainDb = gainDb;
      this.q = q;
      this.order = order;
      this.channel = channel;
    }

    static Band fromMap(Map<?, ?> map) {
      return new Band(
          ((Number) map.get("id")).intValue(),
          ((Number) map.get("type")).intValue(),
          ((Number) map.get("frequency")).doubleValue(),
          ((Number) map.get("gain")).doubleValue(),
          ((Number) map.get("q")).doubleValue(),
          ((Number) map.get("order")).intValue(),
          ((Number) map.get("channel")).intValue());
    }

    int sectionCount() {
      return type == TYPE_LOW_PASS || type == TYPE_HIGH_PASS ? Math.max(1, order / 2) : 1;
    }

    /// cascaded low/high passes above 12 dB/oct are butterworth.
    double sectionQ(int section) {
      final int sections = sectionCount();
      if (sections == 1) return q;
      return 1.0 / (2.0 * Math.cos((2.0 * section + 1.0) * Math.PI / (4.0 * sections)));
    }

    /// linear trapezoidal SVF coefficients {a1, a2, a3, m0, m1, m2}, the same response as the RBJ cookbook.
    void design(int section, double sampleRate, double[] out, int offset) {
      final double f = Math.min(frequency, sampleRate * 0.49);
      double g = Math.tan(Math.PI * f / sampleRate);
      double k = 1.0 / sectionQ(section);
      double m0;
      double m1;
      double m2;
      switch (type) {
        case TYPE_PEAK: {
          final double a = Math.pow(10.0, gainDb / 40.0);
          k = 1.0 / (q * a);
          m0 = 1;
          m1 = k * (a * a - 1);
          m2 = 0;
          break;
        }
        case TYPE_LOW_SHELF: {
          final double a = Math.pow(10.0, gainDb / 40.0);
          g /= Math.sqrt(a);
          m0 = 1;
          m1 = k * (a - 1);
          m2 = a * a - 1;
          break;
        }
        case TYPE_HIGH_SHELF: {
          final double a = Math.pow(10.0, gainDb / 40.0);
          g *= Math.sqrt(a);
          m0 = a * a;
          m1 = k * (1 - a) * a;
          m2 = 1 - a * a;
          break;
        }
        case TYPE_LOW_PASS:
          m0 = 0;
          m1 = 0;
          m2 = 1;
          break;
        case TYPE_HIGH_PASS:
          m0 = 1;
          m1 = -k;
          m2 = -1;
          break;
        case TYPE_BAND_PASS:
          m0 = 0;
          m1 = k;
          m2 = 0;
          break;
        case TYPE_NOTCH:
          m0 = 1;
          m1 = -k;
          m2 = 0;
          break;
        case TYPE_ALL_PASS:
          m0 = 1;
          m1 = -2 * k;
          m2 = 0;
          break;
        default:
          m0 = 1;
          m1 = 0;
          m2 = 0;
          break;
      }
      final double a1 = 1.0 / (1.0 + g * (g + k));
      final double a2 = g * a1;
      out[offset] = a1;
      out[offset + 1] = a2;
      out[offset + 2] = g * a2;
      out[offset + 3] = m0;
      out[offset + 4] = m1;
      out[offset + 5] = m2;
    }
  }
}
