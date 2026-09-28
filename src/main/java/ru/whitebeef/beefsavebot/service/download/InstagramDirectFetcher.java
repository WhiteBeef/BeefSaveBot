package ru.whitebeef.beefsavebot.service.download;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Ссылка на ролик Instagram без yt-dlp: со страницы встраивания (её Instagram отдаёт без входа
 * в аккаунт) или из метатега og:video. Запасной путь, когда yt-dlp получает отказ.
 */
@Slf4j
@Component
public class InstagramDirectFetcher {

  private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
  private static final Pattern SHORTCODE = Pattern.compile(
      "instagram\\.com/(?:[\\w.]+/)?(?:p|reel|reels|tv)/([\\w-]+)");
  private static final Pattern VIDEO_URL = Pattern.compile(
      "\"video_url\"\\s*:\\s*\"(https?://[^\"]+)\"");
  private static final Pattern OG_VIDEO = Pattern.compile(
      "<meta[^>]+property=\"og:video(?::secure_url)?\"[^>]+content=\"([^\"]+)\"");

  private final HttpClient httpClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .cookieHandler(new CookieManager())
      .connectTimeout(Duration.ofSeconds(15))
      .build();

  public static String shortcode(String url) {
    Matcher matcher = SHORTCODE.matcher(url == null ? "" : url);
    return matcher.find() ? matcher.group(1) : null;
  }

  /**
   * @return прямая ссылка на mp4 или {@code null}, если найти не удалось
   */
  public String findVideoUrl(String url) {
    String code = shortcode(url);
    if (code == null) {
      return null;
    }
    for (String page : List.of(
        "https://www.instagram.com/p/" + code + "/embed/captioned/",
        "https://www.instagram.com/reel/" + code + "/")) {
      try {
        HttpResponse<String> response = httpClient.send(request(page),
            HttpResponse.BodyHandlers.ofString());
        String video = parseVideoUrl(response.body());
        if (video != null) {
          return video;
        }
        log.debug("На странице {} нет ссылки на видео (HTTP {})", page, response.statusCode());
      } catch (Exception e) {
        log.debug("Не удалось загрузить {}: {}", page, e.getMessage());
      }
    }
    return null;
  }

  public void download(String videoUrl, Path target) throws IOException, InterruptedException {
    HttpResponse<Path> response = httpClient.send(request(videoUrl),
        HttpResponse.BodyHandlers.ofFile(target));
    if (response.statusCode() != 200 || Files.size(target) == 0) {
      Files.deleteIfExists(target);
      throw new IOException("HTTP " + response.statusCode() + " при скачивании видео Instagram");
    }
  }

  /**
   * Ищет ссылку на видео в HTML: поле video_url (в том числе внутри экранированного JSON) или
   * метатег og:video.
   */
  static String parseVideoUrl(String html) {
    if (html == null) {
      return null;
    }
    // JSON бывает экранирован несколько раз (JSON внутри строки JavaScript) — снимаем все уровни
    Matcher json = VIDEO_URL.matcher(unescapeAll(html));
    if (json.find()) {
      return json.group(1);
    }
    Matcher og = OG_VIDEO.matcher(html);
    if (og.find()) {
      return og.group(1).replace("&amp;", "&");
    }
    return null;
  }

  private static String unescapeAll(String value) {
    String current = value;
    for (int i = 0; i < 5; i++) {
      // Сначала схлопываем двойные слеши, иначе \\u0026 превратится в \&
      String next = current.replace("\\\\", "\\")
          .replace("\\/", "/")
          .replace("\\\"", "\"")
          .replace("\\u0026", "&");
      if (next.equals(current)) {
        break;
      }
      current = next;
    }
    return current.replace("&amp;", "&");
  }

  private HttpRequest request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Referer", "https://www.instagram.com/")
        .GET()
        .build();
  }
}
