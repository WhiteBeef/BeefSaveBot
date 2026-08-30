package ru.whitebeef.beefsavebot.service;


import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.whitebeef.beefsavebot.configuration.BotConfiguration;
import ru.whitebeef.beefsavebot.dto.RequestDto;
import ru.whitebeef.beefsavebot.dto.UserInfoDto;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.service.download.MediaType;
import ru.whitebeef.beefsavebot.service.download.VideoDownloadService;
import ru.whitebeef.beefsavebot.service.download.YandexMusicDownloadService;
import ru.whitebeef.beefsavebot.service.download.YandexMusicDownloadService.TrackSearchResult;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService extends TelegramLongPollingBot {

  private static final String TRACK_CALLBACK_PREFIX = "ym_track:";
  private static final int BUTTON_TEXT_LIMIT = 64;

  private final BotConfiguration botConfig;
  private final VideoDownloadService videoDownloadService;
  private final YandexMusicDownloadService yandexMusicDownloadService;
  private final RequestService requestService;
  private final ExecutorService executorService = Executors.newFixedThreadPool(10);

  @PostConstruct
  public void registerBot() throws Exception {
    TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
    try {
      api.registerBot(this);
    } catch (Exception exception) {
      log.error("Не удалось зарегистрировать бота, проверьте token и username в конфигурации");
    }
  }

  @Override
  public void onUpdateReceived(Update update) {
    if (update.hasCallbackQuery()) {
      executorService.execute(() -> this.executeCallback(update));
      return;
    }
    if (!update.hasMessage() || !update.getMessage().hasText()) {
      return;
    }
    executorService.execute(() -> this.executeUpdate(update));
  }

  public void executeUpdate(Update update) {
    String url = update.getMessage().getText();
    Long chatId = update.getMessage().getChatId();
    User user = update.getMessage().getFrom();

    RequestLog requestLog = requestService.saveRequest(RequestDto.builder()
        .url(url)
        .downloaded(false)
        .userInfoDto(UserInfoDto.builder()
            .username(user.getUserName())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .telegramUserId(user.getId())
            .build())
        .build());

    File file = null;
    try {
      if (!videoDownloadService.canDownloadVideo(url)) {
        offerSearchResults(chatId, url);
        return;
      }

      MediaType mediaType = videoDownloadService.getMediaType(url);
      execute(SendMessage.builder()
          .chatId(chatId.toString())
          .text(mediaType == MediaType.AUDIO ? "Ваш трек выгружается.. Ожидайте!"
              : "Ваше видео выгружается.. Ожидайте!")
          .build());
      file = videoDownloadService.downloadVideo(url);
      sendMedia(chatId, file, mediaType, requestLog);
    } catch (Exception e) {
      log.error("Ошибка при обработке видео {}: {}", url, e.getMessage());
      try {
        execute(SendMessage.builder()
            .chatId(chatId.toString())
            .text("Ошибка при обработке ссылки. Попробуйте ещё раз, или напишите @WhiteBeef")
            .build());
      } catch (TelegramApiException ex) {
        log.error("Ошибка при отправке сообщения: {}", e.getMessage());
        throw new RuntimeException(ex);
      }
    } finally {
      cleanup(file);
    }
  }

  public void executeCallback(Update update) {
    CallbackQuery callbackQuery = update.getCallbackQuery();
    String data = callbackQuery.getData();
    Long chatId = callbackQuery.getMessage().getChatId();
    if (data == null || !data.startsWith(TRACK_CALLBACK_PREFIX)) {
      return;
    }
    String trackId = data.substring(TRACK_CALLBACK_PREFIX.length());
    User user = callbackQuery.getFrom();

    File file = null;
    try {
      execute(AnswerCallbackQuery.builder().callbackQueryId(callbackQuery.getId()).build());

      RequestLog requestLog = requestService.saveRequest(RequestDto.builder()
          .url("yandex-music-search:" + trackId)
          .downloaded(false)
          .userInfoDto(UserInfoDto.builder()
              .username(user.getUserName())
              .firstName(user.getFirstName())
              .lastName(user.getLastName())
              .telegramUserId(user.getId())
              .build())
          .build());

      execute(SendMessage.builder()
          .chatId(chatId.toString())
          .text("Ваш трек выгружается.. Ожидайте!")
          .build());
      file = yandexMusicDownloadService.downloadTrackById(trackId);
      sendMedia(chatId, file, MediaType.AUDIO, requestLog);
    } catch (Exception e) {
      log.error("Ошибка при обработке трека {}: {}", trackId, e.getMessage());
      try {
        execute(SendMessage.builder()
            .chatId(chatId.toString())
            .text("Ошибка при обработке ссылки. Попробуйте ещё раз, или напишите @WhiteBeef")
            .build());
      } catch (TelegramApiException ex) {
        log.error("Ошибка при отправке сообщения: {}", e.getMessage());
      }
    } finally {
      cleanup(file);
    }
  }

  private void offerSearchResults(Long chatId, String query) throws TelegramApiException {
    List<TrackSearchResult> results = yandexMusicDownloadService.search(query);
    if (results.isEmpty()) {
      execute(SendMessage.builder()
          .chatId(chatId.toString())
          .text(
              "Я пока не умею обрабатывать видео этого типа, и по названию ничего не нашлось в Яндекс Музыке!\nВот сайты, откуда я умею скачивать видео:\n\n"
                  + videoDownloadService.getSupportedSites()
                  + "\n\nСвяжитесь с @WhiteBeef, если вам необходим какой-то сайт, которого нет в списке :0")
          .build());
      return;
    }

    List<List<InlineKeyboardButton>> keyboard = results.stream()
        .map(result -> List.of(InlineKeyboardButton.builder()
            .text(truncate(result.display()))
            .callbackData(TRACK_CALLBACK_PREFIX + result.trackId())
            .build()))
        .toList();

    execute(SendMessage.builder()
        .chatId(chatId.toString())
        .text("Не смог распознать ссылку, но нашёл похожее в Яндекс Музыке:")
        .replyMarkup(InlineKeyboardMarkup.builder().keyboard(keyboard).build())
        .build());
  }

  private String truncate(String text) {
    return text.length() <= BUTTON_TEXT_LIMIT ? text
        : text.substring(0, BUTTON_TEXT_LIMIT - 1) + "…";
  }

  private void sendMedia(Long chatId, File file, MediaType mediaType, RequestLog requestLog)
      throws TelegramApiException, IOException {
    long size = Files.size(file.toPath());
    log.info("Размер файла: {} bytes", size);
    if (size > 50L * 1024 * 1024) {
      execute(SendMessage.builder()
          .chatId(chatId.toString())
          .text(mediaType == MediaType.AUDIO
              ? "К сожалению трек слишком большой :(\nМаксимальный размер - 50Мб!"
              : "К сожалению видео слишком длинное :(\nМаксимальный размер - 50Мб!")
          .build());
      return;
    }
    if (mediaType == MediaType.AUDIO) {
      execute(SendAudio.builder()
          .chatId(chatId.toString())
          .audio(new InputFile(file))
          .build());
    } else {
      execute(SendVideo.builder()
          .chatId(chatId.toString())
          .video(new InputFile(file))
          .build());
    }
    requestLog.setDownloaded(true);
    requestService.save(requestLog);
  }

  private void cleanup(File file) {
    try {
      if (file != null) {
        Files.deleteIfExists(file.toPath());
        File parent = file.getParentFile();
        if (parent != null) {
          Files.deleteIfExists(parent.toPath());
        }
      }
    } catch (IOException e) {
      log.error("Ошибка при удалении временного файла");
    }
  }

  @Override
  public String getBotUsername() {
    return botConfig.getBotUsername();
  }

  @Override
  public String getBotToken() {
    return botConfig.getBotToken();
  }
}
