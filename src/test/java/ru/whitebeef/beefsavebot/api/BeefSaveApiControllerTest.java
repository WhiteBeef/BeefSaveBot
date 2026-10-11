package ru.whitebeef.beefsavebot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.service.TelegramBotService;
import ru.whitebeef.beefsavebot.service.convert.ConverterRegistry;
import ru.whitebeef.beefsavebot.service.download.VideoDownloadService;
import ru.whitebeef.beefsavebot.service.download.MediaType;
import ru.whitebeef.beefsavebot.service.media.MediaProcessingService;
import ru.whitebeef.beefsavebot.service.media.MediaSender;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;
import ru.whitebeef.beefsavebot.service.music.MusicSearchService;

/** REST API для интеграций: ключ, файл в ответе до предела, остальное — ботом в Telegram. */
@WebMvcTest(BeefSaveApiController.class)
@Import({ApiKeyFilter.class, ApiJobs.class})
@TestPropertySource(properties = {"api.key=k3y", "api.inline-max-bytes=100"})
class BeefSaveApiControllerTest {

  @Autowired
  private MockMvc mvc;
  @MockitoBean
  private VideoDownloadService videos;
  @MockitoBean
  private MediaProcessingService media;
  @MockitoBean
  private MediaSender sender;
  @MockitoBean
  private MusicSearchService music;
  @MockitoBean
  private ConverterRegistry converters;
  @MockitoBean
  private DownloadConfiguration download;
  @MockitoBean
  private TelegramBotService bot;

  private File video(int bytes) throws Exception {
    File f = File.createTempFile("video", ".mp4");
    Files.write(f.toPath(), new byte[bytes]);
    return f;
  }

  @Test
  void keyIsRequired() throws Exception {
    mvc.perform(get("/api/v1/info")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/info").header("X-Api-Key", "чужой")).andExpect(status().isUnauthorized());
    when(videos.getSupportedSites()).thenReturn("YouTube, TikTok");
    mvc.perform(get("/api/v1/info").header("X-Api-Key", "k3y")).andExpect(status().isOk())
        .andExpect(jsonPath("$.videoSites").value("YouTube, TikTok"))
        .andExpect(jsonPath("$.inlineMaxBytes").value(100));
  }

  @Test
  void smallFileComesBackInlineAndBigOneIsRefused() throws Exception {
    when(videos.canDownloadVideo("https://youtu.be/x")).thenReturn(true);
    when(videos.getMediaType(any())).thenReturn(MediaType.VIDEO);
    File small = video(10);
    when(videos.downloadVideo(eq("https://youtu.be/x"), any())).thenReturn(small);
    when(media.process(eq(small), eq(OutputFormat.MP4), any(), isNull())).thenReturn(small);
    mvc.perform(post("/api/v1/media").header("X-Api-Key", "k3y").contentType("application/json")
            .content("{\"url\":\"https://youtu.be/x\",\"deliver\":\"inline\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(10))
        .andExpect(jsonPath("$.base64").value("AAAAAAAAAAAAAA=="));
    File big = video(500);
    when(videos.downloadVideo(eq("https://youtu.be/x"), any())).thenReturn(big);
    when(media.process(eq(big), eq(OutputFormat.MP4), any(), isNull())).thenReturn(big);
    mvc.perform(post("/api/v1/media").header("X-Api-Key", "k3y").contentType("application/json")
            .content("{\"url\":\"https://youtu.be/x\",\"deliver\":\"inline\"}"))
        .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").value(containsString("Telegram")));
  }

  @Test
  void telegramDeliveryIsAJobAndTheBotSendsIt() throws Exception {
    when(videos.canDownloadVideo(any())).thenReturn(true);
    when(videos.getMediaType(any())).thenReturn(MediaType.VIDEO);
    File f = video(500);
    when(videos.downloadVideo(any(), any())).thenReturn(f);
    when(media.process(any(), any(), any(), any())).thenReturn(f);
    when(sender.send(any(), eq("42"), isNull(), any(), eq(OutputFormat.MP4), anyBoolean())).thenReturn(List.of());
    mvc.perform(post("/api/v1/media").header("X-Api-Key", "k3y").contentType("application/json")
            .content("{\"url\":\"https://youtu.be/x\",\"start\":\"0:10\",\"end\":\"0:20\",\"chatId\":\"42\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.jobId").exists());
    verify(sender, timeout(3000)).send(any(), eq("42"), isNull(), any(), eq(OutputFormat.MP4), anyBoolean());
    mvc.perform(post("/api/v1/media").header("X-Api-Key", "k3y").contentType("application/json")
            .content("{\"url\":\"https://youtu.be/x\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("chatId")));
  }

  @Test
  void cropNeedsBothEndsInOrder() {
    assertThat(BeefSaveApiController.crop(null, "")).isNull();
    assertThat(BeefSaveApiController.crop("1:00", "1:30").start().seconds()).isEqualTo(60);
    assertThatThrownBy(() -> BeefSaveApiController.crop("1:00", null)).isInstanceOf(UserFacingException.class);
    assertThatThrownBy(() -> BeefSaveApiController.crop("2:00", "1:00")).hasMessageContaining("позже");
  }
}
