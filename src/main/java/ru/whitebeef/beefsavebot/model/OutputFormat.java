package ru.whitebeef.beefsavebot.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OutputFormat {
  MP4("mp4", "MP4", "видео со звуком, открывается везде"),
  MP3("mp3", "MP3", "только звук"),
  WEBM("webm", "WEBM", "видео VP9/Opus"),
  WEBP("webp", "WEBP", "анимация без звука, как гифка");

  private final String extension;
  private final String title;
  private final String description;

  public boolean isAudioOnly() {
    return this == MP3;
  }

  public static OutputFormat parse(String value) {
    for (OutputFormat format : values()) {
      if (format.name().equalsIgnoreCase(value)) {
        return format;
      }
    }
    return null;
  }
}
