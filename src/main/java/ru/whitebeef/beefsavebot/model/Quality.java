package ru.whitebeef.beefsavebot.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Quality {
  LOW("Низкое", 360, 96, 28, 38, 10, 320, 50),
  MEDIUM("Среднее", 720, 192, 24, 34, 15, 480, 65),
  HIGH("Высокое", Integer.MAX_VALUE, 320, 21, 31, 20, 640, 80);

  private final String title;
  /**
   * Предпочтительная максимальная высота видео (жёсткий потолок задаётся в конфиге).
   */
  private final int maxHeight;
  private final int audioKbps;
  private final int x264Crf;
  private final int vp9Crf;
  private final int webpFps;
  private final int webpWidth;
  private final int webpQuality;

  public static Quality parse(String value) {
    for (Quality quality : values()) {
      if (quality.name().equalsIgnoreCase(value)) {
        return quality;
      }
    }
    return null;
  }
}
