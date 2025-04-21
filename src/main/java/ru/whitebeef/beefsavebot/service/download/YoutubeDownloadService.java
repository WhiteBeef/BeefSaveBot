package ru.whitebeef.beefsavebot.service.download;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class YoutubeDownloadService implements DownloadService {

  private static final Logger log = LoggerFactory.getLogger(YoutubeDownloadService.class);
  private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
          "^(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/)|youtu\\.be/)([\\w-]{11})(?:[?&]\\S*)?$")
      .asMatchPredicate();

  public File downloadVideo(String url) {
    try {
      String filename = "video_" + UUID.randomUUID() + ".mp4";

      ProcessBuilder builder = new ProcessBuilder(
          "yt-dlp", "-f", "best[height<=720][ext=mp4]", "-o", filename, url
      );
      builder.inheritIO();

      Process process = builder.start();
      int exitCode = process.waitFor();

      if (exitCode != 0) {
        throw new RuntimeException("yt-dlp завершился с ошибкой, код: " + exitCode);
      }

      File file = new File(filename);
      if (!file.exists()) {
        throw new RuntimeException("Загруженный файл не найден: " + filename);
      }

      return file;
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
}