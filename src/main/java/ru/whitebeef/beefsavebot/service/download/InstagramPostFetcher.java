package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;

/**
 * Состав поста Instagram: картинки, видео и музыка. Фото-посты yt-dlp не скачивает, но умеет
 * достать их данные, поэтому вызываем его как библиотеку из небольшого скрипта
 * {@code scripts/instagram_post.py}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstagramPostFetcher {

  private static final String SCRIPT = "scripts/instagram_post.py";
  private static final String DOCKER_PYTHON = "/opt/ytdlp-venv/bin/python";
  private static final long TIMEOUT_SECONDS = 90;

  private final DownloadConfiguration downloadConfiguration;
  private final ObjectMapper mapper = new ObjectMapper();
  private volatile Path script;

  public record Post(String title, String username, List<Item> items, Music music) {

    /**
     * Только картинки, без видео: такой пост склеивается в слайд-шоу.
     */
    public boolean isPhotoPost() {
      return !items.isEmpty() && items.stream().noneMatch(Item::video);
    }

    public List<String> imageUrls() {
      return items.stream().filter(item -> !item.video()).map(Item::url).toList();
    }

    public String firstVideoUrl() {
      return items.stream().filter(Item::video).map(Item::url).findFirst().orElse(null);
    }
  }

  public record Item(boolean video, String url) {

  }

  /**
   * @param startMs    с какого места трека играет музыка в посте
   * @param durationMs сколько она играет
   */
  public record Music(String url, Long startMs, Long durationMs, String title, String artist) {

  }

  /**
   * @return пост или {@code null}, если получить его не удалось
   */
  public Post fetch(String url) {
    List<String> command = new ArrayList<>();
    try {
      command.addAll(List.of(python(), script().toString(), url));
    } catch (IOException e) {
      log.warn("Не удалось подготовить скрипт {}: {}", SCRIPT, e.getMessage());
      return null;
    }
    String cookies = downloadConfiguration.getYtDlpCookiesFile();
    if (cookies != null && !cookies.isBlank()) {
      command.addAll(List.of("--cookies", cookies));
    }
    String userAgent = downloadConfiguration.getYtDlpUserAgent();
    if (userAgent != null && !userAgent.isBlank()) {
      command.addAll(List.of("--user-agent", userAgent));
    }
    try {
      log.info("Читаю состав поста Instagram {}", url);
      Process process = new ProcessBuilder(command)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
      String output = new String(process.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8);
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        log.warn("Состав поста {} не получен за {} с", url, TIMEOUT_SECONDS);
        return null;
      }
      Post post = parse(output);
      if (post == null) {
        log.warn("Состав поста {} не получен: {}", url, output.strip());
      } else {
        log.info("Пост {}: {} элементов, музыка: {}", url, post.items().size(),
            post.music() != null);
      }
      return post;
    } catch (IOException e) {
      log.warn("Не удалось запустить {}: {}", command.get(0), e.getMessage());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * Разбирает вывод скрипта: последняя непустая строка — JSON.
   */
  Post parse(String output) {
    String json = output == null ? "" : output.strip();
    int lastLine = json.lastIndexOf('\n');
    if (lastLine >= 0) {
      json = json.substring(lastLine + 1);
    }
    if (json.isEmpty()) {
      return null;
    }
    try {
      JsonNode root = mapper.readTree(json);
      if (root.hasNonNull("error") || !root.path("items").isArray()) {
        return null;
      }
      List<Item> items = new ArrayList<>();
      for (JsonNode item : root.path("items")) {
        String itemUrl = text(item, "url");
        if (itemUrl != null) {
          items.add(new Item("video".equals(text(item, "type")), itemUrl));
        }
      }
      JsonNode musicNode = root.path("music");
      Music music = text(musicNode, "url") == null ? null : new Music(text(musicNode, "url"),
          number(musicNode, "start_ms"), number(musicNode, "duration_ms"),
          text(musicNode, "title"), text(musicNode, "artist"));
      return new Post(text(root, "title"), text(root, "username"), items, music);
    } catch (IOException e) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }

  private static Long number(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNumber() ? value.asLong() : null;
  }

  private String python() {
    String configured = downloadConfiguration.getYtDlpPython();
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    return Files.isExecutable(Path.of(DOCKER_PYTHON)) ? DOCKER_PYTHON : "python3";
  }

  /**
   * Скрипт лежит внутри jar, а Python нужен файл на диске.
   */
  private Path script() throws IOException {
    Path current = script;
    if (current != null && Files.exists(current)) {
      return current;
    }
    synchronized (this) {
      if (script != null && Files.exists(script)) {
        return script;
      }
      Path dir = Files.createTempDirectory("instagram_script_");
      Path file = dir.resolve("instagram_post.py");
      try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCRIPT)) {
        if (in == null) {
          throw new IOException("нет ресурса " + SCRIPT);
        }
        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
      }
      script = file;
      return file;
    }
  }
}
