package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;

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
  public File downloadVideo(String url) {
    Matcher matcher = URL_PATTERN.matcher(url.trim());
    if (!matcher.matches()) {
      throw new RuntimeException("Не удалось разобрать ссылку на трек Яндекс Музыки");
    }
    String trackId = matcher.group(1);
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
    File file = new File(tempDir.toFile(), buildFileName(trackId, token) + ".mp3");
    try {
      log.info("Запрос на скачивание трека Яндекс Музыки {}", trackId);

      JsonNode downloadInfoEntry = findBestDownloadInfo(trackId, token);
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
      return file;
    } catch (Exception e) {
      try {
        Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(tempDir);
      } catch (IOException ignored) {
        // best-effort cleanup
      }
      log.error("Ошибка при загрузке трека Яндекс Музыки {}: {}", url, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  private String buildFileName(String trackId, String token) {
    String fallback = "audio_" + UUID.randomUUID();
    try {
      HttpRequest request = HttpRequest.newBuilder(
              URI.create("https://api.music.yandex.net/tracks/" + trackId))
          .header("Authorization", "OAuth " + token)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("Не удалось получить метаданные трека {} для имени файла: HTTP {} {}",
            trackId, response.statusCode(), response.body());
        return fallback;
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
        return fallback;
      }
      String name = artists == null || artists.isBlank() ? title : artists + " - " + title;
      String sanitized = sanitizeFileName(name);
      return sanitized.isBlank() ? fallback : sanitized;
    } catch (Exception e) {
      log.warn("Не удалось получить название трека {} для имени файла: {}", trackId, e.getMessage());
      return fallback;
    }
  }

  private String sanitizeFileName(String name) {
    String sanitized = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
    if (sanitized.length() > 150) {
      sanitized = sanitized.substring(0, 150).trim();
    }
    return sanitized;
  }

  private JsonNode findBestDownloadInfo(String trackId, String token) throws IOException, InterruptedException {
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
    for (JsonNode entry : results) {
      if (entry.path("preview").asBoolean(false)) {
        continue;
      }
      if (!"mp3".equals(entry.path("codec").asText())) {
        continue;
      }
      if (best == null
          || entry.path("bitrateInKbps").asInt(0) > best.path("bitrateInKbps").asInt(0)) {
        best = entry;
      }
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
