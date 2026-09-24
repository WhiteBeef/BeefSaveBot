package ru.whitebeef.beefsavebot.service.music;

import java.io.File;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.download.YandexMusicDownloadService;

@Component
@RequiredArgsConstructor
public class YandexMusicSearchProvider implements MusicSearchProvider {

  private final YandexMusicDownloadService yandexMusicDownloadService;
  private final DownloadConfiguration downloadConfiguration;

  @Override
  public MusicProvider provider() {
    return MusicProvider.YANDEX;
  }

  @Override
  public boolean isAvailable() {
    String token = downloadConfiguration.getYandexMusicToken();
    return token != null && !token.isBlank();
  }

  @Override
  public List<TrackResult> search(String query, int limit) {
    return yandexMusicDownloadService.search(query).stream()
        .limit(limit)
        .map(track -> new TrackResult(MusicProvider.YANDEX, track.trackId(), track.artist(),
            track.title()))
        .toList();
  }

  @Override
  public File download(TrackResult track, Quality quality) {
    return yandexMusicDownloadService.downloadTrackById(track.id(), quality);
  }
}
