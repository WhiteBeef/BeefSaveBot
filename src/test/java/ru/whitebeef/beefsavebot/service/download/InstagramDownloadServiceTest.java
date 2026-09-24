package ru.whitebeef.beefsavebot.service.download;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

class InstagramDownloadServiceTest {

  private static final String URL = "https://www.instagram.com/reel/DdoLnWEI9dC/?stkn=x";
  private static final long MAX_BYTES = 50L * 1024 * 1024;

  @TempDir
  Path tempDir;
  private YtDlpClient ytDlpClient;
  private InstagramDownloadService service;

  @BeforeEach
  void setUp() throws Exception {
    DownloadConfiguration configuration = new DownloadConfiguration();
    configuration.setMaxHeight(1080);
    ytDlpClient = mock(YtDlpClient.class);
    service = new InstagramDownloadService(configuration, ytDlpClient);
    File file = Files.createFile(tempDir.resolve("reel.mp4")).toFile();
    when(ytDlpClient.download(anyString(), anyString(), any(), anyList(), anyString()))
        .thenReturn(file);
  }

  /**
   * Вертикальный рилс: прогрессивные mp4 без сведений о кодеках и DASH-дорожки 1080×1920.
   * Раньше все форматы отсеивались по высоте 1920 > 1080 или из-за неизвестных кодеков.
   */
  @Test
  void verticalReelWithUnknownCodecsIsDownloaded() throws Exception {
    when(ytDlpClient.fetchMetadata(anyString(), anyList())).thenReturn(new ObjectMapper()
        .readTree("""
            {"title": "reel", "duration": 20, "formats": [
              {"format_id": "1", "width": 720, "height": 1280, "url": "https://a.mp4"},
              {"format_id": "2", "width": 1080, "height": 1920, "url": "https://b.mp4"},
              {"format_id": "dash-v", "vcodec": "avc1.64001f", "acodec": "none",
               "width": 1080, "height": 1920, "tbr": 2500},
              {"format_id": "dash-a", "vcodec": "none", "acodec": "mp4a.40.2", "tbr": 128}
            ]}"""));

    service.downloadVideo(URL, new DownloadOptions(Quality.HIGH, false, MAX_BYTES));

    // Лучший вариант — 1080p (короткая сторона); DASH-пара с известным размером идёт первой
    verify(ytDlpClient).download(eq(URL), argThat(spec -> spec.equals("dash-v+dash-a")
        || spec.equals("2")), eq("mp4"), anyList(), anyString());
  }

  @Test
  void mediumQualityPicks720pVertical() throws Exception {
    when(ytDlpClient.fetchMetadata(anyString(), anyList())).thenReturn(new ObjectMapper()
        .readTree("""
            {"title": "reel", "duration": 20, "formats": [
              {"format_id": "1", "width": 720, "height": 1280},
              {"format_id": "2", "width": 1080, "height": 1920}
            ]}"""));

    service.downloadVideo(URL, new DownloadOptions(Quality.MEDIUM, false, MAX_BYTES));

    verify(ytDlpClient).download(eq(URL), eq("1"), eq("mp4"), anyList(), anyString());
  }

  @Test
  void letsYtDlpChooseWhenFormatsAreNotDescribed() throws Exception {
    when(ytDlpClient.fetchMetadata(anyString(), anyList())).thenReturn(new ObjectMapper()
        .readTree("""
            {"title": "reel", "formats": [{"format_id": "0", "url": "https://x"}]}"""));

    service.downloadVideo(URL, new DownloadOptions(Quality.HIGH, false, MAX_BYTES));

    verify(ytDlpClient).download(eq(URL), argThat(spec -> spec.startsWith("bv*[filesize<?")),
        eq("mp4"), argThat(args -> args.contains("-S")), anyString());
  }
}
