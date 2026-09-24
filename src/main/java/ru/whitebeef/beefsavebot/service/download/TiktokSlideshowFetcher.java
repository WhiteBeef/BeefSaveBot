package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Достаёт картинки и музыку фото-поста (слайд-шоу) TikTok. yt-dlp такие посты не поддерживает.
 * Сначала разбирается JSON со страницы поста, а если не вышло — запасной API tikwm.com.
 */
@Slf4j
@Component
public class TiktokSlideshowFetcher {

  private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
  private static final String REFERER = "https://www.tiktok.com/";
  private static final Pattern UNIVERSAL_DATA = Pattern.compile(
      "<script[^>]+id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
  private static final Pattern SIGI_STATE = Pattern.compile(
      "<script[^>]+id=\"SIGI_STATE\"[^>]*>(.*?)</script>", Pattern.DOTALL);
  private static final Pattern POST_ID = Pattern.compile("/(?:photo|video)/(\\d+)");

  private final DownloadConfiguration downloadConfiguration;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .cookieHandler(new CookieManager())
      .connectTimeout(Duration.ofSeconds(15))
      .build();

  public TiktokSlideshowFetcher(DownloadConfiguration downloadConfiguration) {
    this.downloadConfiguration = downloadConfiguration;
  }

  /**
   * @param imageUrls ссылки на картинки по порядку
   * @param musicUrl  ссылка на музыку или {@code null}
   */
  public record Slideshow(List<String> imageUrls, String musicUrl, String title,
                          String author) {

  }

  /**
   * Прямая ссылка на видео (без водяного знака, если есть) — запасной путь, когда не справился
   * yt-dlp.
   */
  public record VideoPost(String videoUrl, String title) {

  }

  public static boolean isPhotoUrl(String url) {
    return url != null && url.contains("/photo/");
  }

  /**
   * Раскрывает короткие ссылки (vt.tiktok.com, vm.tiktok.com) в полные. При ошибке сети
   * возвращает исходную ссылку.
   */
  public String resolve(String url) {
    try {
      HttpResponse<Void> response = httpClient.send(request(url).build(),
          HttpResponse.BodyHandlers.discarding());
      return response.uri().toString();
    } catch (Exception e) {
      log.debug("Не удалось раскрыть ссылку {}: {}", url, e.getMessage());
      return url;
    }
  }

  public Slideshow fetch(String url) {
    List<String> problems = new ArrayList<>();
    for (String pageUrl : pageUrls(url)) {
      try {
        HttpResponse<String> response = httpClient.send(request(pageUrl).build(),
            HttpResponse.BodyHandlers.ofString());
        Slideshow slideshow = parsePage(response.body());
        if (slideshow != null) {
          return slideshow;
        }
        problems.add(pageUrl + ": на странице нет данных слайд-шоу (HTTP "
            + response.statusCode() + ")");
      } catch (Exception e) {
        problems.add(pageUrl + ": " + e.getMessage());
      }
    }
    if (downloadConfiguration.isSlideshowFallbackApiEnabled()) {
      try {
        HttpResponse<String> response = httpClient.send(request(
                "https://www.tikwm.com/api/?hd=1&url=" + URLEncoder.encode(url,
                    StandardCharsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString());
        Slideshow slideshow = parseTikwm(response.body());
        if (slideshow != null) {
          return slideshow;
        }
        problems.add("tikwm: нет картинок в ответе");
      } catch (Exception e) {
        problems.add("tikwm: " + e.getMessage());
      }
    }
    log.warn("Не удалось получить слайд-шоу {}: {}", url, problems);
    throw new UserFacingException("Не получилось скачать слайд-шоу из TikTok, попробуйте позже");
  }

  /**
   * Ищет прямую ссылку на видео: сначала в данных страницы поста, затем через tikwm.com.
   */
  public VideoPost fetchVideo(String url) {
    List<String> problems = new ArrayList<>();
    try {
      HttpResponse<String> response = httpClient.send(request(url).build(),
          HttpResponse.BodyHandlers.ofString());
      VideoPost post = parseVideoPage(response.body());
      if (post != null) {
        return post;
      }
      problems.add("на странице нет ссылки на видео (HTTP " + response.statusCode() + ")");
    } catch (Exception e) {
      problems.add("страница: " + e.getMessage());
    }
    if (downloadConfiguration.isSlideshowFallbackApiEnabled()) {
      try {
        HttpResponse<String> response = httpClient.send(request(
                "https://www.tikwm.com/api/?hd=1&url=" + URLEncoder.encode(url,
                    StandardCharsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString());
        VideoPost post = parseTikwmVideo(response.body());
        if (post != null) {
          return post;
        }
        problems.add("tikwm: нет ссылки на видео");
      } catch (Exception e) {
        problems.add("tikwm: " + e.getMessage());
      }
    }
    log.warn("Не удалось получить видео TikTok {} без yt-dlp: {}", url, problems);
    return null;
  }

  static VideoPost parseVideoPage(String html) throws IOException {
    if (html == null) {
      return null;
    }
    Matcher universal = UNIVERSAL_DATA.matcher(html);
    if (!universal.find()) {
      return null;
    }
    JsonNode item = new ObjectMapper().readTree(universal.group(1))
        .path("__DEFAULT_SCOPE__").path("webapp.video-detail").path("itemInfo")
        .path("itemStruct");
    JsonNode video = item.path("video");
    for (String field : List.of("playAddr", "downloadAddr")) {
      String address = video.path(field).asText("");
      if (address.startsWith("http")) {
        return new VideoPost(address, blankToNull(item.path("desc").asText(null)));
      }
    }
    return null;
  }

  static VideoPost parseTikwmVideo(String json) throws IOException {
    JsonNode root = new ObjectMapper().readTree(json);
    if (root.path("code").asInt(-1) != 0) {
      return null;
    }
    JsonNode data = root.path("data");
    for (String field : List.of("hdplay", "play", "wmplay")) {
      String address = data.path(field).asText("");
      if (address.isBlank()) {
        continue;
      }
      if (address.startsWith("/")) {
        address = "https://www.tikwm.com" + address;
      }
      return new VideoPost(address, blankToNull(data.path("title").asText(null)));
    }
    return null;
  }

  /**
   * Сама страница и та же публикация по адресу /video/: TikTok отдаёт данные по обоим.
   */
  private static List<String> pageUrls(String url) {
    List<String> urls = new ArrayList<>(List.of(url));
    if (isPhotoUrl(url)) {
      urls.add(url.replace("/photo/", "/video/"));
    }
    return urls;
  }

  public void download(String url, Path target) throws IOException, InterruptedException {
    HttpResponse<Path> response = httpClient.send(request(url).build(),
        HttpResponse.BodyHandlers.ofFile(target));
    if (response.statusCode() != 200 || Files.size(target) == 0) {
      Files.deleteIfExists(target);
      throw new IOException("HTTP " + response.statusCode() + " при скачивании " + url);
    }
  }

  private HttpRequest.Builder request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("User-Agent", USER_AGENT)
        .header("Referer", REFERER)
        .header("Accept-Language", "en-US,en;q=0.9")
        .GET();
  }

  // ---------------------------------------------------------------- разбор ответов

  /**
   * Разбирает HTML страницы поста. {@code null}, если это не слайд-шоу.
   */
  static Slideshow parsePage(String html) throws IOException {
    if (html == null) {
      return null;
    }
    Matcher universal = UNIVERSAL_DATA.matcher(html);
    if (universal.find()) {
      JsonNode item = new ObjectMapper().readTree(universal.group(1))
          .path("__DEFAULT_SCOPE__").path("webapp.video-detail").path("itemInfo")
          .path("itemStruct");
      Slideshow slideshow = fromItem(item);
      if (slideshow != null) {
        return slideshow;
      }
    }
    Matcher sigi = SIGI_STATE.matcher(html);
    if (sigi.find()) {
      JsonNode items = new ObjectMapper().readTree(sigi.group(1)).path("ItemModule");
      for (Iterator<JsonNode> it = items.elements(); it.hasNext(); ) {
        Slideshow slideshow = fromItem(it.next());
        if (slideshow != null) {
          return slideshow;
        }
      }
    }
    return null;
  }

  private static Slideshow fromItem(JsonNode item) {
    JsonNode images = item.path("imagePost").path("images");
    if (!images.isArray() || images.isEmpty()) {
      return null;
    }
    List<String> urls = new ArrayList<>();
    for (JsonNode image : images) {
      JsonNode urlList = image.path("imageURL").path("urlList");
      if (!urlList.isArray() || urlList.isEmpty()) {
        urlList = image.path("displayImage").path("urlList");
      }
      String best = bestImageUrl(urlList);
      if (best != null) {
        urls.add(best);
      }
    }
    if (urls.isEmpty()) {
      return null;
    }
    JsonNode music = item.path("music");
    String musicUrl = music.path("playUrl").isTextual() ? music.path("playUrl").asText(null)
        : music.path("playUrl").path("urlList").path(0).asText(null);
    return new Slideshow(urls, blankToNull(musicUrl), blankToNull(item.path("desc").asText(null)),
        blankToNull(item.path("author").path("uniqueId").asText(null)));
  }

  /**
   * Предпочитаем JPEG: его точно прочитает ffmpeg.
   */
  private static String bestImageUrl(JsonNode urlList) {
    String first = null;
    for (JsonNode node : urlList) {
      String url = node.asText("");
      if (url.isBlank()) {
        continue;
      }
      if (first == null) {
        first = url;
      }
      String lower = url.toLowerCase(Locale.ROOT);
      if (lower.contains(".jpeg") || lower.contains(".jpg")) {
        return url;
      }
    }
    return first;
  }

  /**
   * Разбирает ответ tikwm.com. {@code null}, если это не слайд-шоу.
   */
  static Slideshow parseTikwm(String json) throws IOException {
    JsonNode root = new ObjectMapper().readTree(json);
    if (root.path("code").asInt(-1) != 0) {
      return null;
    }
    JsonNode data = root.path("data");
    List<String> urls = new ArrayList<>();
    data.path("images").forEach(image -> {
      if (!image.asText("").isBlank()) {
        urls.add(image.asText());
      }
    });
    if (urls.isEmpty()) {
      return null;
    }
    String music = data.path("music").asText(null);
    if (music == null || music.isBlank()) {
      music = data.path("music_info").path("play").asText(null);
    }
    return new Slideshow(urls, blankToNull(music), blankToNull(data.path("title").asText(null)),
        blankToNull(data.path("author").path("unique_id").asText(null)));
  }

  static String postId(String url) {
    Matcher matcher = POST_ID.matcher(url == null ? "" : url);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
