package ru.whitebeef.beefsavebot.service.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.service.download.YoutubeDownloadService;
import ru.whitebeef.beefsavebot.service.download.YoutubeMusicDownloadService;
import ru.whitebeef.beefsavebot.service.download.YtDlpAudioDownloader;

class YoutubeMusicTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void parsesSearchResults() throws Exception {
    List<TrackResult> results = YoutubeMusicSearchProvider.parse(List.of(
        mapper.readTree("""
            {"_type": "url", "id": "dQw4w9WgXcQ", "title": "Never Gonna Give You Up",
             "channel": "Rick Astley - Topic"}"""),
        mapper.readTree("""
            {"_type": "url", "id": "abcdefghijk", "title": "Song",
             "artists": ["A", "B"]}"""),
        mapper.readTree("{\"_type\": \"url\", \"id\": \"UCplaylist123456\", \"title\": \"Channel\"}")));

    assertEquals(2, results.size());
    assertEquals(MusicProvider.YOUTUBE_MUSIC, results.get(0).provider());
    assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", results.get(0).id());
    assertEquals("Rick Astley - Never Gonna Give You Up", results.get(0).display());
    assertEquals("A, B - Song", results.get(1).display());
  }

  @Test
  void artistFromMetadata() throws Exception {
    assertEquals("Kino", YtDlpAudioDownloader.artist(mapper.readTree(
        "{\"artist\": \"Kino\", \"uploader\": \"Some channel\"}")));
    assertEquals("Queen", YtDlpAudioDownloader.artist(mapper.readTree(
        "{\"uploader\": \"Queen - Topic\"}")));
    assertNull(YtDlpAudioDownloader.artist(mapper.readTree("{\"title\": \"x\"}")));
  }

  @Test
  void recognizesYoutubeMusicLinks() {
    YoutubeMusicDownloadService music = new YoutubeMusicDownloadService(null);
    assertTrue(music.canDownloadVideo("https://music.youtube.com/watch?v=dQw4w9WgXcQ"));
    assertTrue(music.canDownloadVideo(
        "https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc&feature=share"));
    assertFalse(music.canDownloadVideo("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    // Обычный YouTube не должен перехватывать ссылки YouTube Music
    assertFalse(new YoutubeDownloadService(null, null)
        .canDownloadVideo("https://music.youtube.com/watch?v=dQw4w9WgXcQ"));
  }
}
