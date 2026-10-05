// by claude
package com.ryanheise.just_audio;

import java.util.List;
import java.util.Map;

/// process-wide, every player's processor reads the latest one. effect types match the native engine's indices.
final class SoundEffects {

  static final int COUNT = 9;

  private static volatile Config config = Config.NONE;

  private SoundEffects() {}

  static Config get() {
    return config;
  }

  static void set(Map<?, ?> map) {
    config = Config.fromMap(map);
  }

  static final class Config {
    static final Config NONE = new Config(0, new float[COUNT]);

    final int enabledMask;
    final float[] intensities;

    private Config(int enabledMask, float[] intensities) {
      this.enabledMask = enabledMask;
      this.intensities = intensities;
    }

    static Config fromMap(Map<?, ?> map) {
      final List<?> effects = (List<?>) map.get("effects");
      final float[] intensities = new float[COUNT];
      int enabledMask = 0;
      for (Object raw : effects) {
        final Map<?, ?> effect = (Map<?, ?>) raw;
        final int type = ((Number) effect.get("type")).intValue();
        if (type < 0 || type >= COUNT) continue;
        enabledMask |= 1 << type;
        intensities[type] = ((Number) effect.get("intensity")).floatValue();
      }
      return new Config(enabledMask, intensities);
    }
  }
}
