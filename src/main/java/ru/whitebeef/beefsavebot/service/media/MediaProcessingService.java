package ru.whitebeef.beefsavebot.service.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.download.YtDlpClient;

/**
 * Конвертация и обрезка медиафайлов через ffmpeg.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingService {

  private static final int MIN_AUDIO_KBPS = 32;

  private final DownloadConfiguration downloadConfiguration;
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Приводит файл к нужному формату и, если задан диапазон, обрезает его.
   *
   * @return исходный файл, если обработка не нужна, иначе новый файл в отдельной временной
   * директории
   */
  public File process(File input, OutputFormat format, Quality quality, CropRange crop)
      throws IOException, InterruptedException {
    if (crop == null && extensionOf(input).equals(format.getExtension())) {
      return input;
    }
    MediaInfo info = probe(input);
    Double start = null;
    Double duration = null;
    if (crop != null) {
      double from = crop.start().toSeconds(info.fps());
      double to = crop.end().toSeconds(info.fps());
      if (to <= from) {
        throw new UserFacingException("Конец фрагмента должен быть позже начала");
      }
      if (info.duration() != null && from >= info.duration()) {
        throw new UserFacingException("Начало фрагмента дальше конца видео (длительность "
            + formatSeconds(info.duration()) + ")");
      }
      if (info.duration() != null) {
        to = Math.min(to, info.duration());
      }
      // Сдвиг на полкадра назад: кадр, начинающийся ровно в таймкоде, гарантированно попадёт в
      // фрагмент, а предыдущий — нет
      double shift = info.fps() != null && info.fps() > 0 ? 0.5 / info.fps() : 0;
      start = Math.max(0, from - shift);
      duration = to - from;
    }
    double resultDuration = duration != null ? duration
        : info.duration() != null ? info.duration() : -1;

    String baseName = baseNameOf(input);
    Path outputDir = Files.createTempDirectory("media_");
    File output = outputDir.resolve(baseName + "." + format.getExtension()).toFile();

    List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
        "-y"));
    if (start != null) {
      // -ss перед -i при перекодировании даёт точную (до кадра) позицию
      command.add("-ss");
      command.add(formatNumber(start));
    }
    command.add("-i");
    command.add(input.getAbsolutePath());
    if (duration != null) {
      command.add("-t");
      command.add(formatNumber(duration));
    }
    command.addAll(encoderArgs(format, quality, info, resultDuration, baseName));
    command.add(output.getAbsolutePath());

    log.info("Запуск ffmpeg: {}", String.join(" ", command));
    run(command, outputDir);
    if (!output.exists() || output.length() == 0) {
      YtDlpClient.deleteDirectory(outputDir);
      throw new RuntimeException("ffmpeg не создал выходной файл");
    }
    return output;
  }

  private List<String> encoderArgs(OutputFormat format, Quality quality, MediaInfo info,
      double durationSeconds, String title) {
    List<String> args = new ArrayList<>();
    switch (format) {
      case MP4 -> {
        if (!info.hasVideo()) {
          throw new UserFacingException("В исходнике нет видео — выберите формат MP3");
        }
        args.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", String.valueOf(quality.getX264Crf()),
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "160k",
            "-movflags", "+faststart"));
      }
      case WEBM -> {
        if (!info.hasVideo()) {
          throw new UserFacingException("В исходнике нет видео — выберите формат MP3");
        }
        args.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?",
            "-c:v", "libvpx-vp9", "-b:v", "0", "-crf", String.valueOf(quality.getVp9Crf()),
            "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
            "-c:a", "libopus", "-b:a", "128k"));
      }
      case WEBP -> {
        if (!info.hasVideo()) {
          throw new UserFacingException("В исходнике нет видео — выберите формат MP3");
        }
        args.addAll(List.of("-map", "0:v:0", "-an",
            "-vf", "fps=" + quality.getWebpFps()
                + ",scale='min(" + quality.getWebpWidth() + ",iw)':-2:flags=lanczos",
            "-c:v", "libwebp", "-lossless", "0", "-q:v", String.valueOf(quality.getWebpQuality()),
            "-compression_level", "4", "-loop", "0"));
      }
      case MP3 -> {
        if (!info.hasAudio()) {
          throw new UserFacingException("В этом видео нет звука");
        }
        args.addAll(List.of("-map", "0:a:0", "-vn",
            "-c:a", "libmp3lame", "-b:a", audioBitrate(quality, durationSeconds) + "k",
            "-id3v2_version", "3"));
        if (info.hasVideo()) {
          // Из видео метаданные трека не переносятся, подписываем файл его названием
          args.addAll(List.of("-metadata", "title=" + title));
        }
      }
    }
    return args;
  }

  /**
   * Битрейт выбранного качества, но не больше, чем позволяет уложиться в лимит размера.
   */
  private int audioBitrate(Quality quality, double durationSeconds) {
    int kbps = quality.getAudioKbps();
    if (durationSeconds > 0) {
      long fitting = (long) (downloadConfiguration.getMaxBytes() * 8 * 0.95
          / durationSeconds / 1000);
      kbps = (int) Math.max(MIN_AUDIO_KBPS, Math.min(kbps, fitting));
    }
    return kbps;
  }

  /**
   * Исполнитель и название для отправки аудио в Telegram. Берутся из тегов файла, а если их нет —
   * из имени вида «Исполнитель - Название».
   */
  public AudioTags readAudioTags(File file) {
    String performer = null;
    String title = null;
    try {
      Process process = new ProcessBuilder("ffprobe", "-v", "error", "-print_format", "json",
          "-show_entries", "format_tags=artist,title", file.getAbsolutePath())
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start();
      String json = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
        JsonNode tags = mapper.readTree(json).path("format").path("tags");
        performer = textOrNull(tags, "artist");
        title = textOrNull(tags, "title");
      }
    } catch (Exception e) {
      log.debug("Не удалось прочитать теги {}: {}", file.getName(), e.getMessage());
    }
    String baseName = baseNameOf(file);
    int separator = baseName.indexOf(" - ");
    if (performer == null && separator > 0) {
      performer = baseName.substring(0, separator).trim();
    }
    if (title == null) {
      title = separator > 0 ? baseName.substring(separator + 3).trim() : baseName;
    }
    return new AudioTags(performer, title);
  }

  private static String textOrNull(JsonNode tags, String field) {
    // В разных контейнерах ключи тегов бывают в разном регистре
    for (var it = tags.fields(); it.hasNext(); ) {
      var entry = it.next();
      if (entry.getKey().equalsIgnoreCase(field) && !entry.getValue().asText("").isBlank()) {
        return entry.getValue().asText().trim();
      }
    }
    return null;
  }

  public record AudioTags(String performer, String title) {

  }

  private MediaInfo probe(File file) throws IOException, InterruptedException {
    List<String> command = List.of("ffprobe", "-v", "error", "-print_format", "json",
        "-show_format", "-show_streams", file.getAbsolutePath());
    Process process = new ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    String json = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
      process.destroyForcibly();
      throw new RuntimeException("ffprobe не смог прочитать файл " + file.getName());
    }
    JsonNode root = mapper.readTree(json);
    boolean hasVideo = false;
    boolean hasAudio = false;
    Double fps = null;
    for (JsonNode stream : root.path("streams")) {
      String type = stream.path("codec_type").asText();
      if ("audio".equals(type)) {
        hasAudio = true;
      } else if ("video".equals(type)
          && stream.path("disposition").path("attached_pic").asInt(0) == 0 && !hasVideo) {
        hasVideo = true;
        fps = parseRate(stream.path("avg_frame_rate").asText(null));
        if (fps == null) {
          fps = parseRate(stream.path("r_frame_rate").asText(null));
        }
      }
    }
    double duration = root.path("format").path("duration").asDouble(-1);
    return new MediaInfo(hasVideo, hasAudio, fps, duration > 0 ? duration : null);
  }

  private Double parseRate(String rate) {
    if (rate == null || rate.isBlank()) {
      return null;
    }
    try {
      String[] parts = rate.split("/");
      double value = parts.length == 2
          ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1])
          : Double.parseDouble(parts[0]);
      return Double.isFinite(value) && value > 0 ? value : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void run(List<String> command, Path outputDir) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).inheritIO().start();
    if (!process.waitFor(downloadConfiguration.getFfmpegTimeoutMinutes(), TimeUnit.MINUTES)) {
      process.destroyForcibly();
      YtDlpClient.deleteDirectory(outputDir);
      throw new UserFacingException("Обработка заняла слишком много времени. "
          + "Попробуйте фрагмент покороче или качество пониже");
    }
    if (process.exitValue() != 0) {
      YtDlpClient.deleteDirectory(outputDir);
      throw new RuntimeException("ffmpeg завершился с кодом " + process.exitValue());
    }
  }

  private static String extensionOf(File file) {
    String name = file.getName();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String baseNameOf(File file) {
    String name = file.getName();
    int dot = name.lastIndexOf('.');
    return dot <= 0 ? name : name.substring(0, dot);
  }

  private static String formatNumber(double value) {
    return String.format(Locale.ROOT, "%.6f", value);
  }

  public static String formatSeconds(double seconds) {
    long total = (long) seconds;
    long hours = total / 3600;
    long minutes = total % 3600 / 60;
    long secs = total % 60;
    return hours > 0 ? String.format("%d:%02d:%02d", hours, minutes, secs)
        : String.format("%d:%02d", minutes, secs);
  }

  private record MediaInfo(boolean hasVideo, boolean hasAudio, Double fps, Double duration) {

  }
}
