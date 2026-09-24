package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Общая логика выбора формата и скачивания через yt-dlp.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractYtDlpDownloadService implements DownloadService {

  protected final DownloadConfiguration downloadConfiguration;
  protected final YtDlpClient ytDlpClient;

  @Override
  public File downloadVideo(String url, DownloadOptions options) {
    try {
      JsonNode root = ytDlpClient.fetchMetadata(url, extraArgs());
      JsonNode formats = root.path("formats");
      if (!formats.isArray()) {
        throw new RuntimeException("Нет массива formats в JSON");
      }
      log.info("Найдено форматов: {}", formats.size());
      double duration = root.path("duration").asDouble(-1);

      List<JsonNode> videos = new ArrayList<>(), audios = new ArrayList<>(), muxeds = new ArrayList<>();
      for (JsonNode format : formats) {
        if (format.path("format_id").asText(null) == null) {
          continue;
        }
        boolean hasVideo = hasVideo(format);
        boolean hasAudio = hasAudio(format);
        if (hasVideo && hasAudio) {
          muxeds.add(format);
        } else if (hasVideo) {
          videos.add(format);
        } else if (hasAudio) {
          audios.add(format);
        }
      }
      log.info("Форматы сгруппированы: muxed={}, video={}, audio={}",
          muxeds.size(), videos.size(), audios.size());

      List<JsonNode> audioTrack = selectAudioTrack(audios);
      List<Candidate> candidates = options.audioOnly()
          ? audioCandidates(audioTrack, duration, options)
          : List.of();
      boolean audioOnlyDownload = !candidates.isEmpty();
      if (candidates.isEmpty()) {
        // Если отдельной аудиодорожки нет, качаем видео (для звука хватит низкого качества),
        // а звук потом извлечёт ffmpeg
        Quality quality = options.audioOnly() ? Quality.LOW : options.quality();
        candidates = videoCandidates(muxeds, videos, audios, audioTrack, duration, quality,
            options.maxSourceBytes());
      }
      if (candidates.isEmpty()) {
        // Форматы описаны не полностью (так бывает у Instagram) — пусть yt-dlp выберет сам,
        // а размер проверим после скачивания
        int preferred = Math.min(maxDimension(), options.quality().getMaxHeight());
        log.info("Подходящих форматов не нашлось, выбор формата доверяем yt-dlp");
        long max = options.maxSourceBytes();
        candidates = List.of(new Candidate(options.audioOnly() && !audios.isEmpty()
            ? "ba[filesize<?" + max + "]/b[filesize<?" + max + "]"
            : "bv*[filesize<?" + max + "]+ba/b[filesize<?" + max + "]", 0, -1,
            List.of("-S", "res:" + preferred + ",ext:mp4:m4a,vcodec:h264")));
        audioOnlyDownload = options.audioOnly() && !audios.isEmpty();
      }
      log.info("Кандидаты собраны. Количество: {}", candidates.size());

      String fileNameBase = YtDlpClient.fileNameBase(root);
      for (Candidate candidate : candidates) {
        log.info("Попытка скачать формат {} ({}p)", candidate.formatSpec(), candidate.dimension());
        List<String> args = new ArrayList<>(extraArgs());
        args.addAll(candidate.extraArgs());
        File file = ytDlpClient.download(url, candidate.formatSpec(),
            audioOnlyDownload ? null : "mp4", args, fileNameBase);
        if (file == null) {
          continue;
        }
        if (file.length() <= options.maxSourceBytes()) {
          return file;
        }
        YtDlpClient.deleteDirectory(file.getParentFile().toPath());
      }

      throw new UserFacingException("Все подходящие варианты оказались больше "
          + options.maxSourceBytes() / 1024 / 1024 + " МБ :(");
    } catch (IOException | InterruptedException e) {
      log.error("Ошибка при загрузке видео с помощью yt-dlp", e);
      throw new RuntimeException(e);
    }
  }

  private List<Candidate> audioCandidates(List<JsonNode> audioTrack, double duration,
      DownloadOptions options) {
    return audioTrack.stream()
        .map(audio -> new Candidate(audio.path("format_id").asText(), 0,
            YtDlpClient.estimateSize(audio, duration), List.of()))
        .filter(candidate -> candidate.size() <= options.maxSourceBytes())
        .sorted(Comparator.comparingLong(Candidate::size).reversed())
        .toList();
  }

  private List<Candidate> videoCandidates(List<JsonNode> muxeds, List<JsonNode> videos,
      List<JsonNode> audios, List<JsonNode> audioTrack, double duration, Quality quality,
      long maxBytes) {
    int maxDimension = maxDimension();
    List<Candidate> candidates = new ArrayList<>();

    if (allowMuxed(audios)) {
      for (JsonNode muxed : muxeds) {
        if (!videoCodecOk(muxed) || !audioCodecOk(muxed)) {
          continue;
        }
        int dimension = dimensionOf(muxed);
        long size = YtDlpClient.estimateSize(muxed, duration);
        if (dimension <= maxDimension && size <= maxBytes) {
          candidates.add(new Candidate(muxed.path("format_id").asText(), dimension, size,
              List.of()));
        }
      }
    }

    // Лучшая (самая объёмная) совместимая дорожка идёт первой
    List<JsonNode> compatibleAudios = audioTrack.stream()
        .filter(this::audioCodecOk)
        .sorted(Comparator.comparingLong(
            (JsonNode audio) -> YtDlpClient.estimateSize(audio, duration)).reversed())
        .toList();
    for (JsonNode video : videos) {
      if (!videoCodecOk(video)) {
        continue;
      }
      int dimension = dimensionOf(video);
      if (dimension > maxDimension) {
        continue;
      }
      long videoSize = YtDlpClient.estimateSize(video, duration);
      for (JsonNode audio : compatibleAudios) {
        long audioSize = YtDlpClient.estimateSize(audio, duration);
        long combinedSize = videoSize < 0 || audioSize < 0 ? -1 : videoSize + audioSize;
        if (combinedSize <= maxBytes) {
          candidates.add(new Candidate(
              video.path("format_id").asText() + "+" + audio.path("format_id").asText(),
              dimension, combinedSize, List.of()));
          break;
        }
      }
    }

    int preferred = Math.min(maxDimension, quality.getMaxHeight());
    // Сначала лучшее разрешение, не превышающее выбранное качество, затем ближайшее большее
    candidates.sort(Comparator
        .comparingInt((Candidate c) -> c.dimension() <= preferred ? 0 : 1)
        .thenComparingInt(c -> c.dimension() <= preferred ? -c.dimension() : c.dimension())
        .thenComparing(Comparator.comparingLong(Candidate::size).reversed()));
    return candidates;
  }

  /**
   * yt-dlp пишет "none", если дорожки точно нет, и не пишет ничего, если кодек неизвестен.
   */
  private static String rawCodec(JsonNode format, String field) {
    JsonNode value = format.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }

  private static boolean hasDimensions(JsonNode format) {
    return format.path("width").asInt(0) > 0 || format.path("height").asInt(0) > 0;
  }

  private static boolean hasVideo(JsonNode format) {
    String codec = rawCodec(format, "vcodec");
    return codec == null ? hasDimensions(format) : !"none".equals(codec);
  }

  private static boolean hasAudio(JsonNode format) {
    String codec = rawCodec(format, "acodec");
    if (codec != null) {
      return !"none".equals(codec);
    }
    // Кодеки неизвестны, но есть кадр — обычно это обычный mp4 со звуком
    return rawCodec(format, "vcodec") == null && hasDimensions(format);
  }

  private boolean videoCodecOk(JsonNode format) {
    String codec = rawCodec(format, "vcodec");
    return codec == null || isCompatibleVideoCodec(codec);
  }

  private boolean audioCodecOk(JsonNode format) {
    String codec = rawCodec(format, "acodec");
    return codec == null || isCompatibleAudioCodec(codec);
  }

  protected abstract boolean isCompatibleVideoCodec(String codec);

  protected abstract boolean isCompatibleAudioCodec(String codec);

  /**
   * Размер, по которому ограничивается и выбирается качество: короткая сторона кадра, чтобы
   * вертикальное видео 1080×1920 считалось 1080p, а не 1920p.
   */
  protected int dimensionOf(JsonNode format) {
    int width = format.path("width").asInt(0);
    int height = format.path("height").asInt(0);
    return width > 0 && height > 0 ? Math.min(width, height) : height;
  }

  protected int maxDimension() {
    return downloadConfiguration.getMaxHeight();
  }

  protected List<String> extraArgs() {
    return List.of();
  }

  /**
   * Отбирает аудиоформаты нужной дорожки (например, оригинальной, а не автоперевода).
   */
  protected List<JsonNode> selectAudioTrack(List<JsonNode> audios) {
    return audios;
  }

  protected boolean allowMuxed(List<JsonNode> audios) {
    return true;
  }

  private record Candidate(String formatSpec, int dimension, long size, List<String> extraArgs) {

  }
}
