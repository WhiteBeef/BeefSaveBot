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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Video;
import org.telegram.telegrambots.meta.api.objects.inlinequery.ChosenInlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.whitebeef.beefsavebot.configuration.BotConfiguration;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.service.download.MediaType;
import ru.whitebeef.beefsavebot.service.download.VideoDownloadService;
import ru.whitebeef.beefsavebot.service.media.MediaProcessingService;
import ru.whitebeef.beefsavebot.service.media.MediaSender;
import java.io.RandomAccessFile;
import java.util.List;

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
        mediaProcessingService, userService, requestService, mediaCacheService,
        new MediaSender(mediaProcessingService));

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
    when(bot.execute(any(SendVideo.class))).thenReturn(videoMessage("video-id"));
  }

  @Test
  void queryThenChosenReplacesTextPlaceholderWithVideo() throws Exception {
    InlineQuery query = new InlineQuery();
    query.setId("q1");
    query.setFrom(user);
    query.setQuery(URL + " 0:10 0:20");
    handler.handleQuery(bot, query);

    ArgumentCaptor<AnswerInlineQuery> answer = ArgumentCaptor.forClass(AnswerInlineQuery.class);
    verify(bot).execute(answer.capture());
    // Текстовая заглушка: отправляется одним нажатием и не показывает чёрное видео
    InlineQueryResultArticle result =
        (InlineQueryResultArticle) answer.getValue().getResults().getFirst();
    assertEquals("⏳ Скачиваю…",
        ((InputTextMessageContent) result.getInputMessageContent()).getMessageText());
    assertTrue(result.getReplyMarkup() != null);
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
    // Служебное сообщение с видео удаляется
    verify(bot).execute(any(DeleteMessage.class));
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

    ArgumentCaptor<EditMessageText> text = ArgumentCaptor.forClass(EditMessageText.class);
    verify(bot).execute(text.capture());
    assertTrue(text.getValue().getText().startsWith("⚠️"));
    verify(bot, never()).execute(any(EditMessageMedia.class));
    verify(requestService).markFailed(any(), any());
  }

  @Test
  void oversizedVideoIsSentInPartsToPrivateChat() throws Exception {
    File big = tempDir.resolve("Big.mp4").toFile();
    try (RandomAccessFile file = new RandomAccessFile(big, "rw")) {
      file.setLength(MediaSender.TELEGRAM_UPLOAD_LIMIT + 1);
    }
    when(mediaProcessingService.process(any(), any(), any(), any())).thenReturn(big);
    Path partsDir = Files.createDirectory(tempDir.resolve("media_parts"));
    List<File> parts = List.of(
        Files.writeString(partsDir.resolve("Big_part000.mp4"), "1").toFile(),
        Files.writeString(partsDir.resolve("Big_part001.mp4"), "2").toFile());
    when(mediaProcessingService.splitIntoParts(eq(big), any(), eq(MediaSender.TELEGRAM_UPLOAD_LIMIT)))
        .thenReturn(parts);
    ChosenInlineQuery chosen = new ChosenInlineQuery();
    chosen.setResultId("unknown");
    chosen.setFrom(user);
    chosen.setInlineMessageId("inline-4");
    chosen.setQuery(URL);
    handler.handleChosen(bot, chosen);

    ArgumentCaptor<SendVideo> sent = ArgumentCaptor.forClass(SendVideo.class);
    verify(bot, org.mockito.Mockito.times(2)).execute(sent.capture());
    assertEquals("42", sent.getAllValues().get(0).getChatId());
    assertEquals("Часть 1/2", sent.getAllValues().get(0).getCaption());
    assertEquals("Часть 2/2", sent.getAllValues().get(1).getCaption());
    ArgumentCaptor<EditMessageText> text = ArgumentCaptor.forClass(EditMessageText.class);
    verify(bot, org.mockito.Mockito.times(2)).execute(text.capture());
    assertTrue(text.getValue().getText().contains("частями"), text.getValue().getText());
    verify(bot, never()).execute(any(EditMessageMedia.class));
    verify(mediaCacheService, never()).put(any(), any());
    assertTrue(!partsDir.toFile().exists());
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
