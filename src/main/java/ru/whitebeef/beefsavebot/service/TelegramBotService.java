package ru.whitebeef.beefsavebot.service;


import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.whitebeef.beefsavebot.configuration.BotConfiguration;
import ru.whitebeef.beefsavebot.dto.RequestDto;
import ru.whitebeef.beefsavebot.dto.UserInfoDto;
import ru.whitebeef.beefsavebot.entity.RequestLog;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService extends TelegramLongPollingBot {

  private final BotConfiguration botConfig;
  private final DownloadService downloadService;
  private final RequestService requestService;

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
    if (!update.hasMessage() || !update.getMessage().hasText()) {
      return;
    }

    String text = update.getMessage().getText();
    Long chatId = update.getMessage().getChatId();
    User user = update.getMessage().getFrom();

    RequestLog requestLog = requestService.saveRequest(RequestDto.builder()
        .url(text)
        .userInfoDto(UserInfoDto.builder()
            .username(user.getUserName())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .telegramUserId(user.getId())
            .build())
        .build());

    File file = null;
    try {
      execute(SendMessage.builder()
          .chatId(chatId.toString())
          .text("Ваше видео выгружается.. Ожидайте!")
          .build());
      file = downloadService.downloadVideo(text);
      execute(SendVideo.builder()
          .chatId(chatId.toString())
          .video(new org.telegram.telegrambots.meta.api.objects.InputFile(file))
          .build());
      requestLog.setDownloaded(true);
      requestService.save(requestLog);
    } catch (Exception e) {
      log.error("Ошибка при обработке видео {}: {}", text, e.getMessage());
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
      try {
        if (file != null) {
          Files.deleteIfExists(file.toPath());
        }
      } catch (IOException e) {
        log.error("Ошибка при удалении временного файла");
      }
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