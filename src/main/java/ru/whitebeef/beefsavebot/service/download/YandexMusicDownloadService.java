package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.model.Quality;

@Service
@Slf4j
@RequiredArgsConstructor
public class YandexMusicDownloadService implements DownloadService {

  // Legacy .jsx handlers yt-dlp relied on are gone (Yandex Music moved to a new
  // frontend), so the track is fetched directly through the mobile/API endpoints instead.
  private static final Pattern URL_PATTERN = Pattern.compile(
      "^(?:https?://)?(?:www\\.)?music\\.yandex\\.(?:ru|com|by|kz|uz)/(?:album/\\d+/)?track/(\\d+)(?:\\?.*)?$");
  private static final Pattern DOWNLOAD_INFO_PATTERN = Pattern.compile(
      "<host>(?<host>[^<]+)</host>.*<path>(?<path>[^<]+)</path>.*<ts>(?<ts>[^<]+)</ts>.*<s>(?<s>[^<]+)</s>",
      Pattern.DOTALL);
  private static final String SIGN_SECRET = "XGRlBW9FXlekgbPrRHuSiA";

  private final DownloadConfiguration downloadConfiguration;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public File downloadVideo(String url, DownloadOptions options) {
    Matcher matcher = URL_PATTERN.matcher(url.trim());
    if (!matcher.matches()) {
      throw new RuntimeException("Не удалось разобрать ссылку на трек Яндекс Музыки");
    }
    return downloadTrackById(matcher.group(1), options.quality());
  }

  public record TrackSearchResult(String trackId, String artist, String title) {
    public String display() {
      return artist == null || artist.isBlank() ? title : artist + " - " + title;
    }
  }

  public List<TrackSearchResult> search(String query) {
    String token = downloadConfiguration.getYandexMusicToken();
    if (token == null || token.isBlank()) {
      log.warn("Поиск по Яндекс Музыке недоступен: не задан download.yandex-music.token");
      return List.of();
    }
    try {
      String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
      HttpRequest request = HttpRequest.newBuilder(
              URI.create("https://api.music.yandex.net/search?type=track&page=0&text=" + encodedQuery))
          .header("Authorization", "OAuth " + token)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("Не удалось выполнить поиск '{}': HTTP {} {}", query, response.statusCode(),
            response.body());
        return List.of();
      }
      JsonNode results = mapper.readTree(response.body())
          .path("result").path("tracks").path("results");
      if (!results.isArray()) {
        return List.of();
      }
      List<TrackSearchResult> found = new ArrayList<>();
      for (JsonNode track : results) {
        String trackId = track.path("id").asText(null);
        String title = track.path("title").asText(null);
        if (trackId == null || title == null || title.isBlank()) {
          continue;
        }
        String artists = StreamSupport.stream(track.path("artists").spliterator(), false)
            .map(artist -> artist.path("name").asText(""))
            .filter(name -> !name.isBlank())
            .reduce((a, b) -> a + ", " + b)
            .orElse(null);
        found.add(new TrackSearchResult(trackId, artists, title));
        if (found.size() == 3) {
          break;
        }
      }
      return found;
    } catch (Exception e) {
      log.warn("Ошибка поиска трека '{}': {}", query, e.getMessage());
      return List.of();
    }
  }

  public File downloadTrackById(String trackId, Quality quality) {
    String token = downloadConfiguration.getYandexMusicToken();
    if (token == null || token.isBlank()) {
      throw new RuntimeException(
          "Скачивание Яндекс Музыки не настроено: не задан download.yandex-music.token");
    }

    Path tempDir;
    try {
      tempDir = Files.createTempDirectory("yandex_track_");
    } catch (IOException e) {
      throw new RuntimeException("Не удалось создать временную директорию для трека", e);
    }
    TrackMeta meta = fetchTrackMeta(trackId, token);
    String fileName = meta == null ? "audio_" + UUID.randomUUID() : meta.fileNameBase();
    File file = new File(tempDir.toFile(), fileName + ".mp3");
    try {
      log.info("Запрос на скачивание трека Яндекс Музыки {}", trackId);

      JsonNode downloadInfoEntry = findDownloadInfo(trackId, token, quality);
      String downloadInfoUrl = downloadInfoEntry.path("downloadInfoUrl").asText(null);
      if (downloadInfoUrl == null) {
        throw new RuntimeException("Не удалось получить downloadInfoUrl трека");
      }

      String directLink = resolveDirectLink(downloadInfoUrl, trackId);
      downloadFile(directLink, file);

      long size = file.length();
      if (size == 0 || size > downloadConfiguration.getMaxBytes()) {
        Files.deleteIfExists(file.toPath());
        throw new RuntimeException("Трек превышает максимально допустимый размер или пуст");
      }
      if (meta != null) {
        writeId3Tags(file, meta);
      }
      return file;
    } catch (Exception e) {
      try {
        Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(tempDir);
      } catch (IOException ignored) {
        // best-effort cleanup
      }
      log.error("Ошибка при загрузке трека Яндекс Музыки {}: {}", trackId, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  record TrackMeta(String artist, String title, String album) {
    String fileNameBase() {
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

  private TrackMeta fetchTrackMeta(String trackId, String token) {
    try {
      HttpRequest request = HttpRequest.newBuilder(
              URI.create("https://api.music.yandex.net/tracks/" + trackId))
          .header("Authorization", "OAuth " + token)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("Не удалось получить метаданные трека {}: HTTP {} {}",
            trackId, response.statusCode(), response.body());
        return null;
      }
      JsonNode results = mapper.readTree(response.body()).path("result");
      JsonNode track = results.isArray() && !results.isEmpty() ? results.get(0) : results;
      String title = track.path("title").asText(null);
      String artists = StreamSupport.stream(track.path("artists").spliterator(), false)
          .map(artist -> artist.path("name").asText(""))
          .filter(name -> !name.isBlank())
          .reduce((a, b) -> a + ", " + b)
          .orElse(null);
      if (title == null || title.isBlank()) {
        log.warn("В ответе метаданных трека {} нет title: {}", trackId, response.body());
        return null;
      }
      String album = track.path("albums").path(0).path("title").asText(null);
      return new TrackMeta(artists, title, album);
    } catch (Exception e) {
      log.warn("Не удалось получить метаданные трека {}: {}", trackId, e.getMessage());
      return null;
    }
  }

  /**
   * Пишет теги через ffmpeg в ID3v2.3 (его понимают все плееры и Telegram) и дублирует в ID3v1.
   */
  void writeId3Tags(File file, TrackMeta meta) {
    File tagged = new File(file.getParentFile(), "tagged_" + file.getName());
    try {
      List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel",
          "error", "-y", "-i", file.getAbsolutePath(), "-map", "0:a", "-c", "copy",
          "-map_metadata", "-1", "-id3v2_version", "3", "-write_id3v1", "1",
          "-metadata", "title=" + meta.title()));
      if (meta.artist() != null && !meta.artist().isBlank()) {
        command.addAll(List.of("-metadata", "artist=" + meta.artist()));
      }
      if (meta.album() != null && !meta.album().isBlank()) {
        command.addAll(List.of("-metadata", "album=" + meta.album()));
      }
      command.add(tagged.getAbsolutePath());
      Process process = new ProcessBuilder(command).inheritIO().start();
      if (!process.waitFor(1, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IOException("ffmpeg не успел записать теги");
      }
      if (process.exitValue() != 0 || tagged.length() == 0) {
        throw new IOException("ffmpeg завершился с кодом " + process.exitValue());
      }
      Files.move(tagged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      tagged.delete();
      log.warn("Не удалось записать ID3-теги трека {} - {}: {}", meta.artist(), meta.title(),
          e.getMessage());
    }
  }

  /**
   * Выбирает mp3 с наибольшим битрейтом, не превышающим битрейт выбранного качества (или самый
   * лёгкий, если все выше).
   */
  private JsonNode findDownloadInfo(String trackId, String token, Quality quality)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(
            URI.create("https://api.music.yandex.net/tracks/" + trackId + "/download-info"))
        .header("Authorization", "OAuth " + token)
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new RuntimeException(
          "Не удалось получить информацию о треке (HTTP " + response.statusCode() + ")");
    }
    JsonNode results = mapper.readTree(response.body()).path("result");
    if (!results.isArray() || results.isEmpty()) {
      throw new RuntimeException("Трек недоступен для скачивания");
    }

    JsonNode best = null;
    JsonNode lightest = null;
    for (JsonNode entry : results) {
      if (entry.path("preview").asBoolean(false)) {
        continue;
      }
      if (!"mp3".equals(entry.path("codec").asText())) {
        continue;
      }
      int bitrate = entry.path("bitrateInKbps").asInt(0);
      if (lightest == null || bitrate < lightest.path("bitrateInKbps").asInt(0)) {
        lightest = entry;
      }
      if (bitrate <= quality.getAudioKbps()
          && (best == null || bitrate > best.path("bitrateInKbps").asInt(0))) {
        best = entry;
      }
    }
    if (best == null) {
      best = lightest;
    }
    if (best == null) {
      throw new RuntimeException(
          "Доступно только превью трека — проверьте валидность download.yandex-music.token");
    }
    return best;
  }

  private String resolveDirectLink(String downloadInfoUrl, String trackId)
      throws IOException, InterruptedException, NoSuchAlgorithmException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(downloadInfoUrl)).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new RuntimeException(
          "Не удалось получить ссылку на скачивание (HTTP " + response.statusCode() + ")");
    }
    Matcher matcher = DOWNLOAD_INFO_PATTERN.matcher(response.body());
    if (!matcher.find()) {
      throw new RuntimeException("Не удалось разобрать ответ с данными для скачивания");
    }
    String host = matcher.group("host");
    String path = matcher.group("path");
    String ts = matcher.group("ts");
    String s = matcher.group("s");

    MessageDigest md5 = MessageDigest.getInstance("MD5");
    byte[] digest = md5.digest((SIGN_SECRET + path.substring(1) + s).getBytes());
    String sign = HexFormat.of().formatHex(digest);

    return "https://" + host + "/get-mp3/" + sign + "/" + ts + path + "?track-id=" + trackId;
  }

  private void downloadFile(String url, File target) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new RuntimeException("Не удалось скачать файл трека (HTTP " + response.statusCode() + ")");
    }
    Files.write(target.toPath(), response.body());
  }

  @Override
  public boolean canDownloadVideo(String url) {
    return URL_PATTERN.matcher(url.trim()).matches();
  }

  @Override
  public List<String> getSupportedSites() {
    return List.of("Яндекс Музыка");
  }

  @Override
  public MediaType getMediaType() {
    return MediaType.AUDIO;
  }
}
