package ru.whitebeef.beefsavebot.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Где искать музыку по названию.
 */
@Getter
@RequiredArgsConstructor
public enum MusicProvider {
  YANDEX("Яндекс Музыка", "🟡"),
  SOUNDCLOUD("SoundCloud", "🟠");

  private final String title;
  private final String emoji;

  public static MusicProvider parse(String value) {
    for (MusicProvider provider : values()) {
      if (provider.name().equalsIgnoreCase(value)) {
        return provider;
      }
    }
    return null;
  }
}
