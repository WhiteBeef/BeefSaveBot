package ru.whitebeef.beefsavebot.service.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.download.TiktokSlideshowFetcher.Slideshow;
import ru.whitebeef.beefsavebot.service.media.SlideshowBuilder;

class TiktokSlideshowTest {

  private static final String PHOTO_URL =
      "https://www.tiktok.com/@mrs.luck_/photo/7689108754792598804?_r=1";

  @TempDir
  Path tempDir;

  @Test
  void parsesUniversalDataPage() throws Exception {
    String html = """
        <html><head>
        <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
        {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
          "id":"7689108754792598804","desc":"Подборка #fyp",
          "author":{"uniqueId":"mrs.luck_"},
          "music":{"playUrl":"https://sf16.tiktokcdn.com/music.mp3","title":"song"},
          "imagePost":{"images":[
            {"imageURL":{"urlList":["https://p16.tiktokcdn.com/a.webp","https://p16.tiktokcdn.com/a.jpeg"]}},
            {"imageURL":{"urlList":["https://p16.tiktokcdn.com/b.webp"]}}
          ]}
        }}}}}
        </script></head></html>
        """;
    Slideshow slideshow = TiktokSlideshowFetcher.parsePage(html);
    assertEquals(List.of("https://p16.tiktokcdn.com/a.jpeg", "https://p16.tiktokcdn.com/b.webp"),
        slideshow.imageUrls());
    assertEquals("https://sf16.tiktokcdn.com/music.mp3", slideshow.musicUrl());
    assertEquals("mrs.luck_", slideshow.author());
  }

  @Test
  void parsesLegacySigiStatePage() throws Exception {
    String html = """
        <script id="SIGI_STATE" type="application/json">
        {"ItemModule":{"123":{"desc":"old","music":{"playUrl":"https://m.mp3"},
          "imagePost":{"images":[{"displayImage":{"urlList":["https://img/1.jpg"]}}]}}}}
        </script>
        """;
    Slideshow slideshow = TiktokSlideshowFetcher.parsePage(html);
    assertEquals(List.of("https://img/1.jpg"), slideshow.imageUrls());
    assertEquals("https://m.mp3", slideshow.musicUrl());
  }

  @Test
  void regularVideoPageIsNotSlideshow() throws Exception {
    String html = """
        <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
        {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
          "video":{"playAddr":"https://v.mp4"}}}}}}
        </script>
        """;
    assertNull(TiktokSlideshowFetcher.parsePage(html));
    assertNull(TiktokSlideshowFetcher.parsePage("<html>captcha</html>"));
  }

  @Test
  void parsesTikwmResponse() throws Exception {
    Slideshow slideshow = TiktokSlideshowFetcher.parseTikwm("""
        {"code":0,"data":{"title":"t","images":["https://i/1.jpeg","https://i/2.jpeg"],
          "music":"https://m.mp3","author":{"unique_id":"u"}}}
        """);
    assertEquals(2, slideshow.imageUrls().size());
    assertEquals("https://m.mp3", slideshow.musicUrl());
    assertNull(TiktokSlideshowFetcher.parseTikwm("{\"code\":-1,\"msg\":\"error\"}"));
    assertNull(TiktokSlideshowFetcher.parseTikwm("{\"code\":0,\"data\":{\"play\":\"v.mp4\"}}"));
  }

  @Test
  void photoLinkIsBuiltIntoSlideshowVideo() throws Exception {
    TiktokSlideshowFetcher fetcher = mock(TiktokSlideshowFetcher.class);
    SlideshowBuilder builder = mock(SlideshowBuilder.class);
    YtDlpClient ytDlp = mock(YtDlpClient.class);
    when(fetcher.resolve("https://vt.tiktok.com/ZSbRvRstH/")).thenReturn(PHOTO_URL);
    when(fetcher.fetch(PHOTO_URL)).thenReturn(new Slideshow(
        List.of("https://i/1.jpeg", "https://i/2.webp"), "https://m.mp3", "Моя подборка #fyp",
        "u"));
    doAnswer(invocation -> Files.writeString(invocation.getArgument(1), "data"))
        .when(fetcher).download(anyString(), any());
    File video = tempDir.resolve("video.mp4").toFile();
    when(builder.build(anyList(), any(), any(), any(), anyString())).thenReturn(video);

    TiktokDownloadService service = new TiktokDownloadService(new DownloadConfiguration(), ytDlp,
        fetcher, builder);
    File result = service.downloadVideo("https://vt.tiktok.com/ZSbRvRstH/",
        new DownloadOptions(Quality.HIGH, false, 50L * 1024 * 1024));

    assertEquals(video, result);
    verify(builder).build(org.mockito.ArgumentMatchers.argThat(images -> images.size() == 2
            && images.get(1).toString().endsWith(".webp")),
        org.mockito.ArgumentMatchers.argThat(music -> music != null), eq(Quality.HIGH), any(),
        eq("Моя подборка"));
    verify(ytDlp, never()).fetchMetadata(anyString(), anyList());
  }

  @Test
  void unsupportedUrlFromYtDlpFallsBackToSlideshow() throws Exception {
    TiktokSlideshowFetcher fetcher = mock(TiktokSlideshowFetcher.class);
    SlideshowBuilder builder = mock(SlideshowBuilder.class);
    YtDlpClient ytDlp = mock(YtDlpClient.class);
    String url = "https://vt.tiktok.com/abc/";
    // Раскрыть ссылку не удалось (например, сеть), yt-dlp ссылку не поддерживает
    when(fetcher.resolve(url)).thenReturn(url);
    when(ytDlp.fetchMetadata(eq(url), anyList())).thenThrow(new UnsupportedUrlException());
    when(fetcher.fetch(url)).thenReturn(new Slideshow(List.of("https://i/1.jpeg"), null, null,
        null));
    doAnswer(invocation -> Files.writeString(invocation.getArgument(1), "data"))
        .when(fetcher).download(anyString(), any());
    when(builder.build(anyList(), any(), any(), any(), anyString()))
        .thenReturn(tempDir.resolve("v.mp4").toFile());

    TiktokDownloadService service = new TiktokDownloadService(new DownloadConfiguration(), ytDlp,
        fetcher, builder);
    service.downloadVideo(url, new DownloadOptions(Quality.LOW, false, 50L * 1024 * 1024));

    verify(builder).build(anyList(), org.mockito.ArgumentMatchers.isNull(), eq(Quality.LOW),
        any(), org.mockito.ArgumentMatchers.startsWith("tiktok_"));
  }

  @Test
  void audioOnlyReturnsSlideshowMusic() throws Exception {
    TiktokSlideshowFetcher fetcher = mock(TiktokSlideshowFetcher.class);
    when(fetcher.resolve(PHOTO_URL)).thenReturn(PHOTO_URL);
    when(fetcher.fetch(PHOTO_URL)).thenReturn(new Slideshow(List.of("https://i/1.jpeg"),
        "https://m.mp3", "Песня", null));
    doAnswer(invocation -> Files.writeString(invocation.getArgument(1), "mp3"))
        .when(fetcher).download(anyString(), any());
    TiktokDownloadService service = new TiktokDownloadService(new DownloadConfiguration(),
        mock(YtDlpClient.class), fetcher, mock(SlideshowBuilder.class));

    File result = service.downloadVideo(PHOTO_URL,
        new DownloadOptions(Quality.HIGH, true, 50L * 1024 * 1024));
    assertEquals("Песня.mp3", result.getName());
    assertTrue(result.exists());
    YtDlpClient.deleteDirectory(result.getParentFile().toPath());
  }

  @Test
  void parsesVideoLinkFromPageAndTikwm() throws Exception {
    TiktokSlideshowFetcher.VideoPost page = TiktokSlideshowFetcher.parseVideoPage("""
        <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
        {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
          "desc":"Видео #fyp","video":{"playAddr":"https://v16.tiktokcdn.com/v.mp4"}}}}}}
        </script>""");
    assertEquals("https://v16.tiktokcdn.com/v.mp4", page.videoUrl());

    TiktokSlideshowFetcher.VideoPost tikwm = TiktokSlideshowFetcher.parseTikwmVideo("""
        {"code":0,"data":{"title":"t","hdplay":"/video/media/hdplay/123.mp4",
          "play":"https://v.mp4"}}""");
    assertEquals("https://www.tikwm.com/video/media/hdplay/123.mp4", tikwm.videoUrl());
    assertNull(TiktokSlideshowFetcher.parseTikwmVideo("{\"code\":-1}"));
  }

  @Test
  void brokenYtDlpFallsBackToDirectDownload() throws Exception {
    TiktokSlideshowFetcher fetcher = mock(TiktokSlideshowFetcher.class);
    YtDlpClient ytDlp = mock(YtDlpClient.class);
    String url = "https://vt.tiktok.com/ZSb8JMELy/";
    String resolved = "https://www.tiktok.com/@u/video/7688661601192283413";
    when(fetcher.resolve(url)).thenReturn(resolved);
    when(ytDlp.fetchMetadata(eq(url), anyList())).thenThrow(new RuntimeException(
        "Не удалось получить метаданные: Unexpected response from webpage request"));
    when(fetcher.fetchVideo(resolved)).thenReturn(
        new TiktokSlideshowFetcher.VideoPost("https://v.mp4", "Смешное видео #fyp"));
    doAnswer(invocation -> Files.writeString(invocation.getArgument(1), "video"))
        .when(fetcher).download(anyString(), any());

    TiktokDownloadService service = new TiktokDownloadService(new DownloadConfiguration(), ytDlp,
        fetcher, mock(SlideshowBuilder.class));
    File result = service.downloadVideo(url,
        new DownloadOptions(Quality.HIGH, false, 50L * 1024 * 1024));

    assertEquals("Смешное видео.mp4", result.getName());
    YtDlpClient.deleteDirectory(result.getParentFile().toPath());
  }
}
