package ru.whitebeef.beefsavebot.service.download;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;

@Service
@Slf4j
@RequiredArgsConstructor
public class YandexMusicDownloadService implements DownloadService {

  private final DownloadConfiguration downloadConfiguration;
  private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
          "^(?:https?://)?(?:www\\.)?music\\.yandex\\.(?:ru|com|by|kz|uz)/(?:album/\\d+/)?track/\\d+(?:\\?.*)?$")
      .asMatchPredicate();

  @Override
  public File downloadVideo(String url) {
    String base = "audio_" + UUID.randomUUID();
    String outTpl = base + ".%(ext)s";
    try {
      log.info("Запрос на скачивание трека Яндекс Музыки: {}", url);
      ProcessBuilder pbDl = new ProcessBuilder(
          "yt-dlp",
          "--no-playlist",
          "-x",
          "--audio-format", "mp3",
          "--audio-quality", "0",
          "-o", outTpl,
          url
      );
      pbDl.inheritIO();
      Process dl = pbDl.start();
      if (dl.waitFor() != 0) {
        throw new RuntimeException("Не удалось скачать трек");
      }

      File file = new File(base + ".mp3");
      if (!file.exists()) {
        throw new RuntimeException("Файл трека не найден после загрузки");
      }
      long size = file.length();
      if (size > downloadConfiguration.getMaxBytes()) {
        file.delete();
        throw new RuntimeException("Трек превышает максимально допустимый размер");
      }
      return file;
    } catch (Exception e) {
      log.error("Ошибка при загрузке трека Яндекс Музыки {}: {}", url, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean canDownloadVideo(String url) {
    return PATTERN_PREDICATE.test(url);
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
