package ru.whitebeef.beefsavebot.service.download;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.model.Quality;

/**
 * Треки YouTube Music по ссылке: сразу в MP3 с исполнителем и названием.
 */
@Service
@RequiredArgsConstructor
public class YoutubeMusicDownloadService implements DownloadService {

  private static final Pattern URL_PATTERN = Pattern.compile(
      "^(?:https?://)?music\\.youtube\\.com/watch\\?\\S*v=[\\w-]{11}\\S*$");

  private final YtDlpAudioDownloader audioDownloader;

  @Override
  public File downloadVideo(String url, DownloadOptions options) {
    return downloadTrack(url, options.quality());
  }

  public File downloadTrack(String url, Quality quality) {
    return audioDownloader.downloadMp3(url, quality, "YouTube Music");
  }

  @Override
  public boolean canDownloadVideo(String url) {
    return URL_PATTERN.matcher(url.trim()).matches();
  }

  @Override
  public List<String> getSupportedSites() {
    return List.of("YouTube Music");
  }

  @Override
  public MediaType getMediaType() {
    return MediaType.AUDIO;
  }
}
