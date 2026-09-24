package ru.whitebeef.beefsavebot.service.music;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.download.SoundCloudDownloadService;
import ru.whitebeef.beefsavebot.service.download.YtDlpClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class SoundCloudSearchProvider implements MusicSearchProvider {

  private final YtDlpClient ytDlpClient;
  private final SoundCloudDownloadService soundCloudDownloadService;

  @Override
  public MusicProvider provider() {
    return MusicProvider.SOUNDCLOUD;
  }

  @Override
  public List<TrackResult> search(String query, int limit) {
    try {
      return parse(ytDlpClient.search("scsearch" + limit + ":" + query));
    } catch (Exception e) {
      log.warn("Ошибка поиска '{}' в SoundCloud: {}", query, e.getMessage());
      return List.of();
    }
  }

  /**
   * Разбирает результаты {@code yt-dlp --flat-playlist -j scsearchN:...}.
   */
  static List<TrackResult> parse(List<JsonNode> entries) {
    List<TrackResult> results = new ArrayList<>();
    for (JsonNode entry : entries) {
      String url = entry.path("webpage_url").asText("");
      if (url.isBlank()) {
        url = entry.path("url").asText("");
      }
      String title = entry.path("title").asText("");
      if (url.isBlank() || title.isBlank()) {
        continue;
      }
      String artist = entry.path("uploader").asText("");
      if (artist.isBlank()) {
        artist = entry.path("artist").asText("");
      }
      results.add(new TrackResult(MusicProvider.SOUNDCLOUD, url,
          artist.isBlank() ? null : artist, title));
    }
    return results;
  }

  @Override
  public File download(TrackResult track, Quality quality) {
    return soundCloudDownloadService.downloadTrack(track.id(), quality);
  }
}
