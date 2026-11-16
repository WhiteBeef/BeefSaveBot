package ru.whitebeef.beefsavebot.service.download;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TrackMeta {

  private final String artist;
  private final String title;
  private final String album;

  public String fileNameBase() {
    String name = artist == null || artist.isBlank() ? title : artist + " - " + title;
    return sanitizeFileName(name);
  }

  private static String sanitizeFileName(String name) {
    String sanitized = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
    if (sanitized.length() > 150) {
      sanitized = sanitized.substring(0, 150).trim();
    }
    return sanitized.isBlank() ? "audio_" + UUID.randomUUID() : sanitized;
  }
}
