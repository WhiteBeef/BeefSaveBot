package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;

@Service
@Slf4j
@RequiredArgsConstructor
public class InstagramDownloadService implements DownloadService {

  private final DownloadConfiguration downloadConfiguration;
  private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
          "^(?:https?://)?(?:www\\.)?instagram\\.com/(?:p|reel|tv)/[\\w-]+/?(?:\\?.*)?$")
      .asMatchPredicate();

  public File downloadVideo(String url) {
    try {
      ObjectMapper mapper = new ObjectMapper();

      log.info("Запрос на получение метаданных для {}", url);
      ProcessBuilder metadataProcessBuilder = new ProcessBuilder(buildMetadataCommand(url));
      log.debug("Команда получения метаданных: {}", String.join(" ", metadataProcessBuilder.command()));
      Process metadataProcess = metadataProcessBuilder.start();
      String metadataJson = new String(metadataProcess.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8);
      if (metadataProcess.waitFor() != 0) {
        throw new RuntimeException("Не удалось получить метаданные");
      }
      log.info("Метаданные получены");
      JsonNode root = mapper.readTree(metadataJson);
      JsonNode formats = root.path("formats");
      if (!formats.isArray()) {
        throw new RuntimeException("Нет массива formats в JSON");
      }
      log.info("Найдено форматов: {}", formats.size());

      List<Candidate> candidates = new ArrayList<>();
      List<JsonNode> videos = new ArrayList<>(), audios = new ArrayList<>(), muxeds = new ArrayList<>();

      for (JsonNode format : formats) {
        String id = format.path("format_id").asText(null);
        if (id == null) {
          continue;
        }
        long size = format.has("filesize") ? format.get("filesize").asLong(-1)
            : format.has("filesize_approx") ? format.get("filesize_approx").asLong(-1)
                : -1;
        if (size < 0) {
          continue;
        }
        String audioCodec = format.path("acodec").asText("none");
        String videoCodec = format.path("vcodec").asText("none");
        boolean hasVideo = !"none".equals(videoCodec);
        boolean hasAudio = !"none".equals(audioCodec);
        if (hasVideo && hasAudio) {
          muxeds.add(format);
        } else if (hasVideo) {
          videos.add(format);
        } else if (hasAudio) {
          audios.add(format);
        }
        log.debug(
            "format_id={} vcodec={} acodec={} height={} width={} filesize={} approx={} tbr={}",
            id,
            videoCodec,
            audioCodec,
            format.path("height").asText("-"),
            format.path("width").asText("-"),
            format.has("filesize") ? format.path("filesize").asText("-") : "-",
            format.has("filesize_approx") ? format.path("filesize_approx").asText("-") : "-",
            format.has("tbr") ? format.path("tbr").asText("-") : "-"
        );
      }

      log.info("Форматы сгруппированы: muxed={}, video={}, audio={}",
          muxeds.size(), videos.size(), audios.size());
      List<JsonNode> compatibleMuxeds = muxeds.stream()
          .filter(m -> isCompatibleVideoCodec(m.path("vcodec").asText()))
          .filter(m -> isCompatibleAudioCodec(m.path("acodec").asText())).toList();

      List<JsonNode> h264Videos = videos.stream()
          .filter(v -> isCompatibleVideoCodec(v.path("vcodec").asText())).toList();

      List<JsonNode> aacAudios = audios.stream()
          .filter(a -> isCompatibleAudioCodec(a.path("acodec").asText())).toList();
      log.info("Совместимые кодеки: muxed={}, video={}, audio={}",
          compatibleMuxeds.size(), h264Videos.size(), aacAudios.size());

      for (JsonNode muxed : compatibleMuxeds) {
        int muxedHeight = muxed.path("height").asInt(0);
        long muxedSize = muxed.has("filesize") ? muxed.get("filesize").asLong()
            : muxed.get("filesize_approx").asLong();
        if (muxedHeight <= downloadConfiguration.getMaxHeight()
            && muxedSize <= downloadConfiguration.getMaxBytes()) {
          candidates.add(new Candidate(muxed.path("format_id").asText(), muxedHeight));
        }
      }

      if (candidates.isEmpty()) {
        for (JsonNode video : h264Videos) {
          int videoHeight = video.path("height").asInt(0);
          if (videoHeight > downloadConfiguration.getMaxHeight()) {
            continue;
          }
          String vid = video.path("format_id").asText();
          long videoSize = video.has("filesize") ? video.get("filesize").asLong()
              : video.get("filesize_approx").asLong();
          for (JsonNode audio : aacAudios) {
            String aid = audio.path("format_id").asText();
            long audioSize = audio.has("filesize") ? audio.get("filesize").asLong()
                : audio.get("filesize_approx").asLong();
            if (videoSize + audioSize <= downloadConfiguration.getMaxBytes()) {
              candidates.add(new Candidate(vid + "+" + aid, videoHeight));
            }
          }
        }
      }

      candidates.sort((a, b) -> Integer.compare(b.height, a.height));
      if (candidates.isEmpty()) {
        throw new RuntimeException("Ни один кандидат ≤50MB не найден");
      }
      log.info("Кандидаты собраны. Количество: {}", candidates.size());
      for (Candidate c : candidates) {
        log.info("Попытка скачать видео с размером {}", c.height);
        String base = "video_" + UUID.randomUUID();
        String outTpl = base + ".%(ext)s";

        ProcessBuilder pbDl = new ProcessBuilder(buildDownloadCommand(url, outTpl, c.combo));
        pbDl.inheritIO();
        Process dl = pbDl.start();
        if (dl.waitFor() != 0) {
          continue;
        }

        File file = new File(base + ".mp4");
        if (!file.exists()) {
          continue;
        }
        long actual = file.length();
        if (actual <= downloadConfiguration.getMaxBytes()) {
          return file;
        } else {
          file.delete();
        }
      }

      throw new RuntimeException("Все подходящие кандидаты превысили 50MB после загрузки");
    } catch (IOException | InterruptedException e) {
      log.error("Ошибка при загрузке видео в YTDLP", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean canDownloadVideo(String url) {
    return PATTERN_PREDICATE.test(url);
  }

  @Override
  public List<String> getSupportedSites() {
    return List.of("Instagram video");
  }

  private List<String> buildMetadataCommand(String url) {
    List<String> command = new ArrayList<>();
    command.add("yt-dlp");
    command.add("--no-playlist");
    String userAgent = downloadConfiguration.getYtDlpUserAgent();
    if (userAgent != null && !userAgent.isBlank()) {
      command.add("--user-agent");
      command.add(userAgent);
    }
    command.add("-j");
    command.add(url);
    return command;
  }

  private List<String> buildDownloadCommand(String url, String outputTemplate, String format) {
    List<String> command = new ArrayList<>();
    command.add("yt-dlp");
    command.add("--no-playlist");
    String userAgent = downloadConfiguration.getYtDlpUserAgent();
    if (userAgent != null && !userAgent.isBlank()) {
      command.add("--user-agent");
      command.add(userAgent);
    }
    command.add("--merge-output-format");
    command.add("mp4");
    command.add("-f");
    command.add(format);
    command.add("-o");
    command.add(outputTemplate);
    command.add(url);
    return command;
  }

  private boolean isCompatibleVideoCodec(String codec) {
    return codec.startsWith("avc1") || codec.startsWith("h264") || codec.startsWith("h265");
  }

  private boolean isCompatibleAudioCodec(String codec) {
    return codec.startsWith("mp4a") || codec.startsWith("aac");
  }

  @Data
  @AllArgsConstructor
  private static class Candidate {
    private String combo;
    private int height;
  }
}
