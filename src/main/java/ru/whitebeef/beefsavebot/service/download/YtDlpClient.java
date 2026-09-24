package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Тонкая обёртка над утилитой yt-dlp.
 */
@Slf4j
@Component
public class YtDlpClient {

  private static final long METADATA_TIMEOUT_MINUTES = 2;
  private static final long DOWNLOAD_TIMEOUT_MINUTES = 20;

  private final ObjectMapper mapper = new ObjectMapper();

  public JsonNode fetchMetadata(String url, List<String> extraArgs)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("yt-dlp");
    command.add("--no-playlist");
    command.addAll(extraArgs);
    command.add("-j");
    command.add(url);

    log.info("Запрос на получение метаданных для {}", url);
    Process process = new ProcessBuilder(command).start();
    StringBuilder stderr = new StringBuilder();
    Thread stderrReader = new Thread(() -> {
      try {
        stderr.append(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException ignored) {
        // best-effort diagnostics
      }
    });
    stderrReader.start();
    String json = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(METADATA_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new RuntimeException("yt-dlp не успел получить метаданные");
    }
    stderrReader.join();
    if (process.exitValue() != 0) {
      if (stderr.indexOf("DRM protected") >= 0) {
        log.warn("Трек защищён DRM: {}", url);
        throw new DrmProtectedException();
      }
      if (stderr.indexOf("Unsupported URL") >= 0) {
        log.warn("yt-dlp не поддерживает ссылку {}", url);
        throw new UnsupportedUrlException();
      }
      log.error("yt-dlp не смог получить метаданные {}: {}", url, stderr);
      throw new RuntimeException("Не удалось получить метаданные: " + stderr);
    }
    log.info("Метаданные получены");
    return mapper.readTree(json);
  }

  /**
   * Поиск через yt-dlp (например, {@code scsearch5:запрос}): по JSON-объекту на каждый результат.
   * Результаты не раскрываются полностью ({@code --flat-playlist}), поэтому поиск быстрый.
   */
  public List<JsonNode> search(String searchQuery) throws IOException, InterruptedException {
    Process process = new ProcessBuilder("yt-dlp", "--flat-playlist", "-j", searchQuery)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(METADATA_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new IOException("yt-dlp не успел выполнить поиск");
    }
    List<JsonNode> results = new ArrayList<>();
    for (String line : output.split("\n")) {
      if (!line.isBlank()) {
        results.add(mapper.readTree(line));
      }
    }
    return results;
  }

  /**
   * Скачивает формат {@code formatSpec} в отдельную временную директорию.
   *
   * @return скачанный файл или {@code null}, если yt-dlp завершился с ошибкой
   */
  public File download(String url, String formatSpec, String mergeOutputFormat,
      List<String> extraArgs, String fileNameBase) throws IOException, InterruptedException {
    Path dir = Files.createTempDirectory("ytdlp_");
    // % в шаблоне имени интерпретируется yt-dlp, поэтому экранируем
    String outputTemplate = dir.resolve(fileNameBase.replace("%", "%%") + ".%(ext)s").toString();

    List<String> command = new ArrayList<>();
    command.add("yt-dlp");
    command.add("--no-playlist");
    command.addAll(extraArgs);
    if (mergeOutputFormat != null) {
      command.add("--merge-output-format");
      command.add(mergeOutputFormat);
    }
    command.add("-f");
    command.add(formatSpec);
    command.add("-o");
    command.add(outputTemplate);
    command.add(url);

    Process process = new ProcessBuilder(command).inheritIO().start();
    boolean finished = process.waitFor(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
    }
    File result = finished && process.exitValue() == 0 ? findResult(dir) : null;
    if (result == null) {
      deleteDirectory(dir);
    }
    return result;
  }

  private File findResult(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> !path.getFileName().toString().endsWith(".part"))
          .max(Comparator.comparingLong(path -> path.toFile().length()))
          .map(Path::toFile)
          .orElse(null);
    }
  }

  public static void deleteDirectory(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException e) {
      log.warn("Не удалось удалить временную директорию {}: {}", dir, e.getMessage());
    }
  }

  public static String fileNameBase(JsonNode metadata) {
    String title = metadata.path("title").asText("");
    return sanitizeFileName(title, "video_" + UUID.randomUUID());
  }

  public static String sanitizeFileName(String name, String fallback) {
    String sanitized = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
    // Точки в начале дают скрытые файлы
    sanitized = sanitized.replaceAll("^\\.+", "").trim();
    if (sanitized.length() > 100) {
      sanitized = sanitized.substring(0, 100).trim();
    }
    return sanitized.isBlank() ? fallback : sanitized;
  }

  public static String codecOf(JsonNode format, String field) {
    JsonNode value = format.path(field);
    return value.isMissingNode() || value.isNull() ? "none" : value.asText("none");
  }

  /**
   * Размер формата в байтах: точный, приблизительный или оценка по битрейту. -1, если неизвестен.
   */
  public static long estimateSize(JsonNode format, double durationSeconds) {
    if (format.hasNonNull("filesize")) {
      return format.path("filesize").asLong(-1);
    }
    if (format.hasNonNull("filesize_approx")) {
      return format.path("filesize_approx").asLong(-1);
    }
    double tbr = format.path("tbr").asDouble(-1);
    if (tbr > 0 && durationSeconds > 0) {
      return (long) (tbr * 125 * durationSeconds);
    }
    return -1;
  }
}
