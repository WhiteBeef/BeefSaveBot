package ru.whitebeef.beefsavebot.service.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.model.Quality;

class YoutubeDownloadServiceTest {

  private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
  private static final long MAX_BYTES = 50L * 1024 * 1024;

  /**
   * Так yt-dlp описывает видео с автопереводом, скачиваемое с немецкого сервера: дорожка по
   * умолчанию — немецкий дубляж, оригинал — английский. Muxed-формат 18 получает язык оригинала из
   * субтитров, хотя содержит дорожку по умолчанию.
   */
  private static final String DUBBED_METADATA = """
      {"title": "Some video", "duration": 100, "formats": [
        {"format_id": "18", "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "height": 360,
         "filesize": 5000000, "language": "en"},
        {"format_id": "140-0", "vcodec": "none", "acodec": "mp4a.40.2", "filesize": 1700000,
         "language": "de", "language_preference": 5,
         "format_note": "German (Germany) (default), medium"},
        {"format_id": "140-1", "vcodec": "none", "acodec": "mp4a.40.2", "filesize": 1600000,
         "language": "en", "language_preference": 10,
         "format_note": "English (United States) original, medium"},
        {"format_id": "251-0", "vcodec": "none", "acodec": "opus", "filesize": 1800000,
         "language": "de", "language_preference": 5},
        {"format_id": "137", "vcodec": "avc1.640028", "acodec": "none", "height": 1080,
         "filesize": 30000000},
        {"format_id": "136", "vcodec": "avc1.4d401f", "acodec": "none", "height": 720,
         "filesize": 15000000},
        {"format_id": "134", "vcodec": "avc1.4d401e", "acodec": "none", "height": 360,
         "filesize": 4000000},
        {"format_id": "248", "vcodec": "vp9", "acodec": "none", "height": 1080,
         "filesize": 20000000}
      ]}""";

  @TempDir
  Path tempDir;
  private YtDlpClient ytDlpClient;
  private YoutubeDownloadService service;

  @BeforeEach
  void setUp() throws Exception {
    DownloadConfiguration configuration = new DownloadConfiguration();
    configuration.setMaxBytes(MAX_BYTES);
    configuration.setMaxHeight(1080);
    ytDlpClient = mock(YtDlpClient.class);
    service = new YoutubeDownloadService(configuration, ytDlpClient);

    JsonNode metadata = new ObjectMapper().readTree(DUBBED_METADATA);
    when(ytDlpClient.fetchMetadata(anyString(), anyList())).thenReturn(metadata);
    File downloaded = Files.createFile(tempDir.resolve("Some video.mp4")).toFile();
    when(ytDlpClient.download(anyString(), anyString(), any(), anyList(), anyString()))
        .thenReturn(downloaded);
  }

  @Test
  void highQualityUsesOriginalAudioTrack() throws Exception {
    service.downloadVideo(URL, new DownloadOptions(Quality.HIGH, false, MAX_BYTES));
    verify(ytDlpClient).download(eq(URL), eq("137+140-1"), eq("mp4"), anyList(), eq("Some video"));
  }

  @Test
  void mediumQualityPrefers720p() throws Exception {
    service.downloadVideo(URL, new DownloadOptions(Quality.MEDIUM, false, MAX_BYTES));
    verify(ytDlpClient).download(eq(URL), eq("136+140-1"), eq("mp4"), anyList(), anyString());
  }

  @Test
  void lowQualityDoesNotUseDubbedMuxedFormat() throws Exception {
    service.downloadVideo(URL, new DownloadOptions(Quality.LOW, false, MAX_BYTES));
    verify(ytDlpClient).download(eq(URL), eq("134+140-1"), eq("mp4"), anyList(), anyString());
  }

  @Test
  void audioOnlyDownloadsOriginalTrack() throws Exception {
    service.downloadVideo(URL, new DownloadOptions(Quality.HIGH, true, MAX_BYTES));
    verify(ytDlpClient).download(eq(URL), eq("140-1"), isNull(), anyList(), anyString());
  }

  @Test
  void selectsOriginalTrackOnly() throws Exception {
    JsonNode formats = new ObjectMapper().readTree(DUBBED_METADATA).path("formats");
    java.util.List<JsonNode> audios = new java.util.ArrayList<>();
    formats.forEach(format -> {
      if ("none".equals(format.path("vcodec").asText())) {
        audios.add(format);
      }
    });
    assertEquals(1, service.selectAudioTrack(audios).size());
    assertEquals("140-1", service.selectAudioTrack(audios).getFirst().path("format_id").asText());
  }
}
