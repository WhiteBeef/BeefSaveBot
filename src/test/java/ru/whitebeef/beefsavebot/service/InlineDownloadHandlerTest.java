package ru.whitebeef.beefsavebot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import ru.whitebeef.beefsavebot.service.cache.CachedMedia;
import ru.whitebeef.beefsavebot.service.cache.MediaCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerInlineQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Video;
import org.telegram.telegrambots.meta.api.objects.inlinequery.ChosenInlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.cached.InlineQueryResultCachedVideo;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.whitebeef.beefsavebot.configuration.BotConfiguration;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.service.download.MediaType;
import ru.whitebeef.beefsavebot.service.download.VideoDownloadService;
import ru.whitebeef.beefsavebot.service.media.MediaProcessingService;

class InlineDownloadHandlerTest {

  private static final String URL = "https://youtu.be/dQw4w9WgXcQ";

  @TempDir
  Path tempDir;
  private AbsSender bot;
  private VideoDownloadService videoDownloadService;
  private MediaProcessingService mediaProcessingService;
  private RequestService requestService;
  private MediaCacheService mediaCacheService;
  private InlineDownloadHandler handler;
  private final User user = new User(42L, "Test", false);

  @BeforeEach
  void setUp() throws Exception {
    bot = mock(AbsSender.class);
    videoDownloadService = mock(VideoDownloadService.class);
    mediaProcessingService = mock(MediaProcessingService.class);
    requestService = mock(RequestService.class);
    UserService userService = mock(UserService.class);
    BotConfiguration config = new BotConfiguration();
    config.setAdminId("1000");
    mediaCacheService = mock(MediaCacheService.class);
    when(mediaCacheService.find(any())).thenReturn(Optional.empty());
    handler = new InlineDownloadHandler(config, new DownloadConfiguration(), videoDownloadService,
        mediaProcessingService, userService, requestService, mediaCacheService);

    UserInfo userInfo = UserInfo.builder().telegramUserId(42L).build();
    when(userService.findByTelegramId(42L)).thenReturn(Optional.of(userInfo));
    when(userService.updateOrCreate(any())).thenReturn(userInfo);
    when(requestService.saveRequest(any(), any(), any(), any(), any()))
        .thenReturn(new RequestLog());
    when(videoDownloadService.canDownloadVideo(URL)).thenReturn(true);
    when(videoDownloadService.getMediaType(URL)).thenReturn(MediaType.VIDEO);
    File video = Files.writeString(tempDir.resolve("Video.mp4"), "video").toFile();
    when(videoDownloadService.downloadVideo(eq(URL), any())).thenReturn(video);
    when(mediaProcessingService.process(any(), any(), any(), any())).thenReturn(video);
    // Первая загрузка — заглушка, вторая — само видео
    when(bot.execute(any(SendVideo.class))).thenReturn(videoMessage("placeholder-id"),
        videoMessage("video-id"));
  }

  @Test
  void queryThenChosenReplacesPlaceholderWithVideo() throws Exception {
    InlineQuery query = new InlineQuery();
    query.setId("q1");
    query.setFrom(user);
    query.setQuery(URL + " 0:10 0:20");
    handler.handleQuery(bot, query);

    ArgumentCaptor<AnswerInlineQuery> answer = ArgumentCaptor.forClass(AnswerInlineQuery.class);
    verify(bot).execute(answer.capture());
    InlineQueryResultCachedVideo result =
        (InlineQueryResultCachedVideo) answer.getValue().getResults().getFirst();
    assertEquals("placeholder-id", result.getVideoFileId());
    assertTrue(result.getTitle().contains("0:10"), result.getTitle());

    ChosenInlineQuery chosen = new ChosenInlineQuery();
    chosen.setResultId(result.getId());
    chosen.setFrom(user);
    chosen.setInlineMessageId("inline-1");
    chosen.setQuery(query.getQuery());
    handler.handleChosen(bot, chosen);

    ArgumentCaptor<EditMessageMedia> edit = ArgumentCaptor.forClass(EditMessageMedia.class);
    verify(bot).execute(edit.capture());
    assertEquals("inline-1", edit.getValue().getInlineMessageId());
    assertEquals("video-id", edit.getValue().getMedia().getMedia());
    // Служебные сообщения (заглушка и видео) удаляются
    verify(bot, org.mockito.Mockito.times(2)).execute(any(DeleteMessage.class));
    verify(requestService).markDownloaded(any(), eq(5L));
    verify(mediaCacheService).put(any(), argThat(media -> "video-id".equals(media.fileId())));
    verify(mediaProcessingService).process(any(), any(), any(),
        org.mockito.ArgumentMatchers.argThat(crop -> crop != null
            && crop.start().seconds() == 10));
  }

  @Test
  void failedDownloadShowsErrorInPlaceOfPlaceholder() throws Exception {
    when(videoDownloadService.downloadVideo(eq(URL), any()))
        .thenThrow(new RuntimeException("boom"));
    ChosenInlineQuery chosen = new ChosenInlineQuery();
    chosen.setResultId("unknown");
    chosen.setFrom(user);
    chosen.setInlineMessageId("inline-2");
    chosen.setQuery(URL);
    handler.handleChosen(bot, chosen);

    ArgumentCaptor<EditMessageCaption> caption = ArgumentCaptor.forClass(EditMessageCaption.class);
    verify(bot).execute(caption.capture());
    assertTrue(caption.getValue().getCaption().startsWith("⚠️"));
    verify(bot, never()).execute(any(EditMessageMedia.class));
    verify(requestService).markFailed(any(), any());
  }

  private static Message videoMessage(String fileId) {
    Message message = new Message();
    message.setMessageId(1);
    Chat chat = new Chat();
    chat.setId(1000L);
    message.setChat(chat);
    Video video = new Video();
    video.setFileId(fileId);
    message.setVideo(video);
    return message;
  }

  @Test
  void cachedVideoIsSentWithoutDownloading() throws Exception {
    when(mediaCacheService.find(any())).thenReturn(Optional.of(
        new CachedMedia("cached-id", CachedMedia.Kind.VIDEO, 1234L)));
    ChosenInlineQuery chosen = new ChosenInlineQuery();
    chosen.setResultId("unknown");
    chosen.setFrom(user);
    chosen.setInlineMessageId("inline-3");
    chosen.setQuery(URL);
    handler.handleChosen(bot, chosen);

    ArgumentCaptor<EditMessageMedia> edit = ArgumentCaptor.forClass(EditMessageMedia.class);
    verify(bot).execute(edit.capture());
    assertEquals("cached-id", edit.getValue().getMedia().getMedia());
    verify(videoDownloadService, never()).downloadVideo(any(), any());
    verify(requestService).markDownloaded(any(), eq(1234L));
  }
}
