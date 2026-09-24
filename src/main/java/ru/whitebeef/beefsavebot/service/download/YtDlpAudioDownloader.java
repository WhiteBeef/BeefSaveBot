package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Скачивает трек через yt-dlp сразу в MP3 с исполнителем и названием в тегах и в имени файла.
 * Используется для SoundCloud и YouTube Music.
 */
@Component
@RequiredArgsConstructor
public class YtDlpAudioDownloader {

  private final YtDlpClient ytDlpClient;

  public File downloadMp3(String url, Quality quality, String serviceName) {
    try {
      JsonNode metadata = ytDlpClient.fetchMetadata(url, List.of());
      String fileName = YtDlpClient.sanitizeFileName(fileName(metadata),
          "audio_" + UUID.randomUUID());
      File file = ytDlpClient.download(url, "bestaudio/best", null, List.of(
          "-x", "--audio-format", "mp3", "--audio-quality", quality.getAudioKbps() + "K",
          "--embed-metadata"), fileName);
      if (file == null) {
        throw new UserFacingException("Не удалось скачать трек с " + serviceName);
      }
      return file;
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException("Ошибка загрузки " + serviceName + ": " + e.getMessage(), e);
    }
  }

  /**
   * «Исполнитель - Название». Если название уже содержит исполнителя, не дублируем его.
   */
  static String fileName(JsonNode metadata) {
    String artist = artist(metadata);
    String title = metadata.path("track").asText("");
    if (title.isBlank()) {
      title = metadata.path("title").asText("");
    }
    return artist == null || title.toLowerCase().contains(artist.toLowerCase()) ? title
        : artist + " - " + title;
  }

  /**
   * Исполнитель из метаданных yt-dlp: artists (список), artist, затем автор загрузки. Каналы
   * YouTube Music вида «Исполнитель - Topic» приводим к имени исполнителя.
   */
  public static String artist(JsonNode metadata) {
    JsonNode artists = metadata.path("artists");
    if (artists.isArray() && !artists.isEmpty()) {
      String joined = StreamSupport.stream(artists.spliterator(), false)
          .map(node -> node.asText(""))
          .filter(name -> !name.isBlank())
          .collect(Collectors.joining(", "));
      if (!joined.isBlank()) {
        return joined;
      }
    }
    for (String field : List.of("artist", "creator", "uploader", "channel")) {
      String value = metadata.path(field).asText("");
      if (!value.isBlank()) {
        return value.replaceAll("\\s+-\\s+Topic$", "").trim();
      }
    }
    return null;
  }
}
