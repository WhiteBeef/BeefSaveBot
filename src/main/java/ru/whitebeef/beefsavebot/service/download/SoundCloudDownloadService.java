package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.music.MusicSearchService;

/**
 * Треки SoundCloud через yt-dlp: сразу в MP3 с исполнителем и названием в тегах.
 */
@Slf4j
@Service
public class SoundCloudDownloadService implements DownloadService {

  private static final Pattern URL_PATTERN = Pattern.compile(
      "^(?:https?://)?(?:www\\.|m\\.|on\\.)?soundcloud\\.(?:com|app\\.goo\\.gl)/\\S+$");

  private final YtDlpAudioDownloader audioDownloader;
  /**
   * Лениво, чтобы не было циклической зависимости через поставщиков музыки.
   */
  private final ObjectProvider<MusicSearchService> musicSearchService;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  public SoundCloudDownloadService(YtDlpAudioDownloader audioDownloader,
      ObjectProvider<MusicSearchService> musicSearchService) {
    this.audioDownloader = audioDownloader;
    this.musicSearchService = musicSearchService;
  }

  /**
   * Прямая ссылка на трек. Если он защищён DRM, узнаём название через oEmbed SoundCloud и ищем
   * тот же трек у других поставщиков музыки.
   */
  @Override
  public File downloadVideo(String url, DownloadOptions options) {
    try {
      return downloadTrack(url, options.quality());
    } catch (DrmProtectedException e) {
      String name = trackName(resolveShortLink(url));
      File replacement = name == null ? null : musicSearchService.getObject()
          .downloadFromOtherProviders(name, MusicProvider.SOUNDCLOUD, options.quality());
      if (replacement == null) {
        throw e;
      }
      return replacement;
    }
  }

  /**
   * «Исполнитель Название» через официальный oEmbed: он работает и для треков с DRM.
   */
  String trackName(String url) {
    try {
      HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(
              "https://soundcloud.com/oembed?format=json&url="
                  + URLEncoder.encode(url, StandardCharsets.UTF_8)))
          .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return null;
      }
      return nameFromOembed(new ObjectMapper().readTree(response.body()));
    } catch (Exception e) {
      log.debug("Не удалось получить название трека {}: {}", url, e.getMessage());
      return null;
    }
  }

  /**
   * В oEmbed title имеет вид «Название by Исполнитель».
   */
  public static String nameFromOembed(JsonNode oembed) {
    String title = oembed.path("title").asText("");
    String author = oembed.path("author_name").asText("");
    if (!author.isBlank() && title.endsWith(" by " + author)) {
      title = title.substring(0, title.length() - author.length() - 4);
    }
    String name = (author + " " + title).trim();
    return name.isBlank() ? null : name;
  }

  public File downloadTrack(String url, Quality quality) {
    return audioDownloader.downloadMp3(resolveShortLink(url), quality, "SoundCloud");
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
