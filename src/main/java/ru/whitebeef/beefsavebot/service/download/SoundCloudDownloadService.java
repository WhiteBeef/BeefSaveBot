package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Треки SoundCloud через yt-dlp: сразу в MP3 с исполнителем и названием в тегах.
 */
@Slf4j
@Service
public class SoundCloudDownloadService implements DownloadService {

  private static final Pattern URL_PATTERN = Pattern.compile(
      "^(?:https?://)?(?:www\\.|m\\.|on\\.)?soundcloud\\.(?:com|app\\.goo\\.gl)/\\S+$");

  private final YtDlpClient ytDlpClient;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  public SoundCloudDownloadService(YtDlpClient ytDlpClient) {
    this.ytDlpClient = ytDlpClient;
  }

  @Override
  public File downloadVideo(String url, DownloadOptions options) {
    return downloadTrack(url, options.quality());
  }

  public File downloadTrack(String url, Quality quality) {
    String trackUrl = resolveShortLink(url);
    try {
      JsonNode metadata = ytDlpClient.fetchMetadata(trackUrl, List.of());
      String artist = firstText(metadata, "artist", "uploader", "creator");
      String title = metadata.path("title").asText("");
      String fileName = YtDlpClient.sanitizeFileName(
          artist == null || title.toLowerCase().contains(artist.toLowerCase()) ? title
              : artist + " - " + title,
          "soundcloud_" + UUID.randomUUID());
      File file = ytDlpClient.download(trackUrl, "bestaudio/best", null, List.of(
          "-x", "--audio-format", "mp3", "--audio-quality", quality.getAudioKbps() + "K",
          "--embed-metadata"), fileName);
      if (file == null) {
        throw new UserFacingException("Не удалось скачать трек с SoundCloud");
      }
      return file;
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException("Ошибка загрузки SoundCloud: " + e.getMessage(), e);
    }
  }

  /**
   * Короткие ссылки on.soundcloud.com раскрываем сами: так надёжнее для yt-dlp.
   */
  private String resolveShortLink(String url) {
    if (!url.contains("on.soundcloud.com") && !url.contains("soundcloud.app.goo.gl")) {
      return url;
    }
    try {
      HttpResponse<Void> response = httpClient.send(HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.discarding());
      return response.uri().toString();
    } catch (Exception e) {
      log.debug("Не удалось раскрыть ссылку {}: {}", url, e.getMessage());
      return url;
    }
  }

  private static String firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      String value = node.path(field).asText("");
      if (!value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  @Override
  public boolean canDownloadVideo(String url) {
    return URL_PATTERN.matcher(url.trim()).matches();
  }

  @Override
  public List<String> getSupportedSites() {
    return List.of("SoundCloud");
  }

  @Override
  public MediaType getMediaType() {
    return MediaType.AUDIO;
  }
}
