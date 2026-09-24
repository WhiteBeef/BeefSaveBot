package ru.whitebeef.beefsavebot.service.music;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.download.YoutubeMusicDownloadService;
import ru.whitebeef.beefsavebot.service.download.YtDlpAudioDownloader;
import ru.whitebeef.beefsavebot.service.download.YtDlpClient;

/**
 * Поиск по YouTube Music через yt-dlp: раздел «Песни» (без клипов и каверов из обычного
 * YouTube).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YoutubeMusicSearchProvider implements MusicSearchProvider {

  private final YtDlpClient ytDlpClient;
  private final YoutubeMusicDownloadService youtubeMusicDownloadService;

  @Override
  public MusicProvider provider() {
    return MusicProvider.YOUTUBE_MUSIC;
  }

  @Override
  public List<TrackResult> search(String query, int limit) {
    try {
      String url = "https://music.youtube.com/search?q="
          + URLEncoder.encode(query, StandardCharsets.UTF_8) + "#songs";
      return parse(ytDlpClient.search(url, List.of("--playlist-end", String.valueOf(limit))))
          .stream().limit(limit).toList();
    } catch (Exception e) {
      log.warn("Ошибка поиска '{}' в YouTube Music: {}", query, e.getMessage());
      return List.of();
    }
  }

  /**
   * Разбирает результаты {@code yt-dlp --flat-playlist -j} для поиска YouTube Music.
   */
  static List<TrackResult> parse(List<JsonNode> entries) {
    List<TrackResult> results = new ArrayList<>();
    for (JsonNode entry : entries) {
      String id = entry.path("id").asText("");
      String title = entry.path("title").asText("");
      if (id.length() != 11 || title.isBlank()) {
        continue;
      }
      results.add(new TrackResult(MusicProvider.YOUTUBE_MUSIC,
          "https://music.youtube.com/watch?v=" + id, YtDlpAudioDownloader.artist(entry), title));
    }
    return results;
  }

  @Override
  public File download(TrackResult track, Quality quality) {
    return youtubeMusicDownloadService.downloadTrack(track.id(), quality);
  }
}
