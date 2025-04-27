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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class YoutubeDownloadService implements DownloadService {

  private static final Long MAX_BYTES = 50L * 1024 * 1024;
  private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
          "^(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/)|youtu\\.be/)([\\w-]{11})(?:[?&]\\S*)?$")
      .asMatchPredicate();

  public File downloadVideo(String url) {
    try {
      ObjectMapper mapper = new ObjectMapper();

      ProcessBuilder pbMeta = new ProcessBuilder("yt-dlp", "-j", url);
      Process meta = pbMeta.start();
      String json = new String(meta.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (meta.waitFor() != 0) {
        throw new RuntimeException("Не удалось получить метаданные");
      }

      JsonNode root = mapper.readTree(json);
      JsonNode formats = root.path("formats");
      if (!formats.isArray()) {
        throw new RuntimeException("Нет массива formats в JSON");
      }

      List<Candidate> cands = new ArrayList<>();
      List<JsonNode> videos = new ArrayList<>(), audios = new ArrayList<>(), muxed = new ArrayList<>();

      for (JsonNode f : formats) {
        String id = f.path("format_id").asText(null);
        if (id == null) {
          continue;
        }
        long size = f.has("filesize") ? f.get("filesize").asLong(-1)
            : f.has("filesize_approx") ? f.get("filesize_approx").asLong(-1)
                : -1;
        if (size < 0) {
          continue;
        }
        String ac = f.path("acodec").asText("none"), vc = f.path("vcodec").asText("none");
        boolean hasV = !"none".equals(vc), hasA = !"none".equals(ac);
        if (hasV && hasA) {
          muxed.add(f);
        } else if (hasV) {
          videos.add(f);
        } else if (hasA) {
          audios.add(f);
        }
      }

      for (JsonNode v : videos) {
        int vh = v.path("height").asInt(0);
        if (vh > 720) {
          continue;
        }
        String vid = v.path("format_id").asText();
        long vsz =
            v.has("filesize") ? v.get("filesize").asLong() : v.get("filesize_approx").asLong();
        for (JsonNode a : audios) {
          String aid = a.path("format_id").asText();
          long asz =
              a.has("filesize") ? a.get("filesize").asLong() : a.get("filesize_approx").asLong();
          if (vsz + asz <= MAX_BYTES) {
            cands.add(new Candidate(vid + "+" + aid, vh));
          }
        }
      }
      for (JsonNode m : muxed) {
        int mh = m.path("height").asInt(0);
        long msz =
            m.has("filesize") ? m.get("filesize").asLong() : m.get("filesize_approx").asLong();
        if (mh <= 720 && msz <= MAX_BYTES) {
          cands.add(new Candidate(m.path("format_id").asText(), mh));
        }
      }

      cands.sort((a, b) -> Integer.compare(b.height, a.height));
      if (cands.isEmpty()) {
        throw new RuntimeException("Ни один кандидат ≤50MB не найден");
      }

      for (Candidate c : cands) {
        String base = "video_" + UUID.randomUUID();
        String outTpl = base + ".%(ext)s";

        ProcessBuilder pbDl = new ProcessBuilder(
            "yt-dlp",
            "--no-playlist",
            "--merge-output-format", "mp4",
            "-f", c.combo,
            "-o", outTpl,
            url
        );
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
        if (actual <= MAX_BYTES) {
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
    return List.of("Youtube video", "Youtube shorts");
  }

  @Data
  @AllArgsConstructor
  private static class Candidate {

    private String combo;
    private int height;
  }
}