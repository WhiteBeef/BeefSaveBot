package ru.whitebeef.beefsavebot.service.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import ru.whitebeef.beefsavebot.service.media.SlideshowBuilder;
import java.util.List;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;

class InstagramDownloadServiceTest {

  private static final String URL = "https://www.instagram.com/reel/DdoLnWEI9dC/?stkn=x";
  private static final long MAX_BYTES = 50L * 1024 * 1024;

  @TempDir
  Path tempDir;
  private YtDlpClient ytDlpClient;
  private InstagramDirectFetcher directFetcher;
  private InstagramPostFetcher postFetcher;
  private SlideshowBuilder slideshowBuilder;
  private InstagramDownloadService service;

  @BeforeEach
  void setUp() throws Exception {
    DownloadConfiguration configuration = new DownloadConfiguration();
    configuration.setMaxHeight(1080);
    ytDlpClient = mock(YtDlpClient.class);
    directFetcher = mock(InstagramDirectFetcher.class);
    postFetcher = mock(InstagramPostFetcher.class);
    slideshowBuilder = mock(SlideshowBuilder.class);
    service = new InstagramDownloadService(configuration, ytDlpClient, directFetcher,
        postFetcher, slideshowBuilder);
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

  @Test
  void blockedYtDlpFallsBackToImpersonationThenDirectLink() throws Exception {
    when(ytDlpClient.fetchMetadata(anyString(), anyList())).thenThrow(new RuntimeException(
        "Не удалось получить метаданные: ERROR: [Instagram] x: HTTP Error 400: Bad Request"));
    when(directFetcher.findVideoUrl(URL)).thenReturn("https://scontent.cdninstagram.com/v.mp4");
    org.mockito.Mockito.doAnswer(invocation -> Files.writeString(invocation.getArgument(1), "v"))
        .when(directFetcher).download(anyString(), any());

    File result = service.downloadVideo(URL,
        new DownloadOptions(Quality.HIGH, false, MAX_BYTES));

    assertEquals("instagram_DdoLnWEI9dC.mp4", result.getName());
    // Вторая попытка yt-dlp — с impersonation
    verify(ytDlpClient).fetchMetadata(eq(URL), argThat(args -> args.contains("--impersonate")));
    YtDlpClient.deleteDirectory(result.getParentFile().toPath());
  }

  @Test
  void everythingFailedGivesFriendlyError() throws Exception {
    when(ytDlpClient.fetchMetadata(anyString(), anyList()))
        .thenThrow(new RuntimeException("HTTP Error 400"));
    when(directFetcher.findVideoUrl(URL)).thenReturn(null);
    org.junit.jupiter.api.Assertions.assertThrows(
        ru.whitebeef.beefsavebot.service.media.UserFacingException.class,
        () -> service.downloadVideo(URL, new DownloadOptions(Quality.HIGH, false, MAX_BYTES)));
  }

  @Test
  void parsesVideoUrlFromPages() {
    // Обычный JSON
    assertEquals("https://scontent.cdninstagram.com/v/t50/a.mp4?efg=1&oh=2",
        InstagramDirectFetcher.parseVideoUrl(
            "{\"video_url\":\"https:\\/\\/scontent.cdninstagram.com\\/v\\/t50\\/a.mp4?efg=1\\u0026oh=2\"}"));
    // JSON внутри строки JavaScript, как на странице /embed/
    assertEquals("https://scontent.cdninstagram.com/b.mp4?x=1&y=2",
        InstagramDirectFetcher.parseVideoUrl(
            "\"contextJSON\":\"{\\\"video_url\\\":\\\"https:\\\\/\\\\/scontent.cdninstagram.com\\\\/b.mp4?x=1\\\\u0026y=2\\\"}\""));
    // Метатег og:video
    assertEquals("https://scontent.cdninstagram.com/c.mp4?a=1&b=2",
        InstagramDirectFetcher.parseVideoUrl(
            "<meta property=\"og:video\" content=\"https://scontent.cdninstagram.com/c.mp4?a=1&amp;b=2\" />"));
    assertNull(InstagramDirectFetcher.parseVideoUrl("<html>login</html>"));
  }

  @Test
  void extractsShortcode() {
    assertEquals("DdzUa_9KeSx", InstagramDirectFetcher.shortcode(
        "https://www.instagram.com/reel/DdzUa_9KeSx/?stkn=MTZvcGJzcGNtM2o0ZA=="));
    assertEquals("Abc-123", InstagramDirectFetcher.shortcode(
        "https://www.instagram.com/some.user/p/Abc-123/"));
    assertEquals("Xyz", InstagramDirectFetcher.shortcode("https://instagram.com/reels/Xyz"));
  }

  @Test
  void photoPostWithMusicBecomesSlideshow() throws Exception {
    String postUrl = "https://www.instagram.com/p/DPhoto123/";
    when(postFetcher.fetch(postUrl)).thenReturn(new InstagramPostFetcher.Post("Отпуск #summer",
        "user", List.of(new InstagramPostFetcher.Item(false, "https://img/1.jpg"),
        new InstagramPostFetcher.Item(false, "https://img/2.jpg")),
        new InstagramPostFetcher.Music("https://music/track.m4a", 12_000L, 15_000L, "Song",
            "Artist")));
    doAnswer(invocation -> Files.writeString(invocation.getArgument(1), "data"))
        .when(directFetcher).download(anyString(), any());
    File video = tempDir.resolve("Отпуск.mp4").toFile();
    when(slideshowBuilder.build(anyList(), any(), anyDouble(), any(), any(), any(), anyString()))
        .thenReturn(video);

    File result = service.downloadVideo(postUrl,
        new DownloadOptions(Quality.HIGH, false, MAX_BYTES));

    assertEquals(video, result);
    verify(slideshowBuilder).build(argThat(images -> images.size() == 2),
        argThat(music -> music != null), eq(12.0), eq(15.0), eq(Quality.HIGH), any(),
        eq("Отпуск"));
    verify(ytDlpClient, never()).fetchMetadata(anyString(), anyList());
  }

  @Test
  void reelDoesNotAskForPostContentsWhenYtDlpWorks() throws Exception {
    when(ytDlpClient.fetchMetadata(anyString(), anyList())).thenReturn(new ObjectMapper()
        .readTree("""
            {"title": "reel", "duration": 20, "formats": [
              {"format_id": "2", "width": 1080, "height": 1920, "url": "https://b.mp4"}
            ]}"""));
    service.downloadVideo(URL, new DownloadOptions(Quality.HIGH, false, MAX_BYTES));
    verify(postFetcher, never()).fetch(anyString());
  }

  @Test
  void parsesHelperOutput() {
    InstagramPostFetcher fetcher = new InstagramPostFetcher(new DownloadConfiguration());
    InstagramPostFetcher.Post post = fetcher.parse("""
        WARNING: something
        {"title": "Hi", "username": "u", "items": [{"type": "image", "url": "https://i/1.jpg"},
         {"type": "video", "url": "https://v/1.mp4"}], "music": {"url": "https://m/1.m4a",
         "start_ms": 5000, "duration_ms": null, "title": "Song", "artist": null}}""".replace("\n ", " "));
    assertEquals(List.of("https://i/1.jpg"), post.imageUrls());
    assertEquals("https://v/1.mp4", post.firstVideoUrl());
    assertEquals(5000L, post.music().startMs());
    assertNull(post.music().durationMs());
    assertNull(fetcher.parse("{\"error\": \"blocked\"}"));
    assertNull(fetcher.parse(""));
  }
}
