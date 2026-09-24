package ru.whitebeef.beefsavebot.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Где искать музыку по названию.
 */
@Getter
@RequiredArgsConstructor
public enum MusicProvider {
  YANDEX("Яндекс Музыка", "Яндекс", "🟡"),
  SOUNDCLOUD("SoundCloud", "SoundCloud", "🟠"),
  YOUTUBE_MUSIC("YouTube Music", "YT Music", "🔴");

  private final String title;
  /**
   * Короткое название для кнопок.
   */
  private final String shortTitle;
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
