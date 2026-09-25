package ru.whitebeef.beefsavebot.service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerInlineQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.inlinequery.ChosenInlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResult;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultsButton;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.cached.InlineQueryResultCachedVideo;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAnimation;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAudio;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.whitebeef.beefsavebot.configuration.BotConfiguration;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.dto.UserInfoDto;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.model.RequestType;
import ru.whitebeef.beefsavebot.service.download.MediaType;
import ru.whitebeef.beefsavebot.service.download.VideoDownloadService;
import ru.whitebeef.beefsavebot.service.download.YtDlpClient;
import ru.whitebeef.beefsavebot.service.media.LinkRequest;
import ru.whitebeef.beefsavebot.service.media.MediaProcessingService;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Инлайн-режим: в любом чате пишем {@code @бот ссылка [начало конец]}, выбираем подсказку — в
 * чат уходит сообщение-заглушка «Скачиваю…», которое бот потом заменяет на видео.
 * <p>
 * Как это устроено: в инлайн-сообщение нельзя загрузить новый файл, можно только подставить уже
 * загруженный по file_id. Поэтому бот загружает результат в служебный чат, берёт file_id,
 * удаляет служебное сообщение и редактирует инлайн-сообщение.
 * <p>
 * Скачивание запускается по событию выбора подсказки (нужно включить inline feedback в
 * BotFather) или по кнопке на заглушке, если feedback выключен.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InlineDownloadHandler {

  public static final String CALLBACK_PREFIX = "inl:";
  private static final Duration PENDING_TTL = Duration.ofMinutes(15);
  private static final long TELEGRAM_UPLOAD_LIMIT = 50L * 1024 * 1024;

  private final BotConfiguration botConfig;
  private final DownloadConfiguration downloadConfiguration;
  private final VideoDownloadService videoDownloadService;
  private final MediaProcessingService mediaProcessingService;
  private final UserService userService;
  private final RequestService requestService;

  /**
   * Подсказки, выбранные пользователем, но ещё не скачанные: ключ — id результата.
   */
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private volatile String placeholderFileId;

  private record Pending(LinkRequest link, long userId, Instant createdAt) {

  }

  public void handleQuery(AbsSender bot, InlineQuery query) throws TelegramApiException {
    log.info("Инлайн-запрос от {}: {}", query.getFrom().getId(), query.getQuery());
    LinkRequest link;
    try {
      link = LinkRequest.parse(query.getQuery());
    } catch (UserFacingException e) {
      answer(bot, query, List.of(), "⚠️ " + e.getMessage());
      return;
    }
    if (link == null) {
      // Пустой ответ без кнопки: ничто не мешает отправить набранный текст обычным сообщением
      answer(bot, query, List.of(), null);
      return;
    }
    if (!videoDownloadService.canDownloadVideo(link.url())) {
      answer(bot, query, List.of(), "Не умею скачивать с этого сайта");
      return;
    }
    UserInfo userInfo = userService.findByTelegramId(query.getFrom().getId()).orElse(null);
    if (userInfo != null && Boolean.TRUE.equals(userInfo.getBanned())
        && !botConfig.isAdmin(userInfo.getTelegramUserId())) {
      answer(bot, query, List.of(), "⛔ Доступ к боту ограничен");
      return;
    }
    String placeholder = placeholder(bot);
    if (placeholder == null) {
      answer(bot, query, List.of(), "Инлайн-режим не настроен");
      return;
    }

    removeExpired();
    String key = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    pending.put(key, new Pending(link, query.getFrom().getId(), Instant.now()));
    String title = "📥 Скачать " + (link.crop() == null ? "видео" : "фрагмент " + link.crop());
    InlineQueryResultCachedVideo result = InlineQueryResultCachedVideo.builder()
        .id(key)
        .videoFileId(placeholder)
        .title(title)
        .description(link.url())
        .caption("⏳ Скачиваю…")
        .replyMarkup(InlineKeyboardMarkup.builder()
            .keyboardRow(List.of(InlineKeyboardButton.builder()
                .text("⏳ Загрузка… (нажмите, если не началась)")
                .callbackData(CALLBACK_PREFIX + key)
                .build()))
            .build())
        .build();
    answer(bot, query, List.of(result), null);
  }

  public void handleChosen(AbsSender bot, ChosenInlineQuery chosen) {
    if (chosen.getInlineMessageId() == null) {
      return;
    }
    Pending request = pending.remove(chosen.getResultId());
    if (request == null) {
      // Например, бот перезапустился между подсказкой и выбором — разбираем запрос заново
      try {
        LinkRequest link = LinkRequest.parse(chosen.getQuery());
        if (link == null) {
          return;
        }
        request = new Pending(link, chosen.getFrom().getId(), Instant.now());
      } catch (UserFacingException e) {
        editCaption(bot, chosen.getInlineMessageId(), "⚠️ " + e.getMessage());
        return;
      }
    }
    download(bot, chosen.getInlineMessageId(), chosen.getFrom(), request.link());
  }

  /**
   * Кнопка на заглушке: запасной способ запуска, если inline feedback в BotFather выключен.
   */
  public void handleCallback(AbsSender bot, CallbackQuery callbackQuery) {
    String key = callbackQuery.getData().substring(CALLBACK_PREFIX.length());
    Pending request = pending.get(key);
    boolean own = request != null && request.userId() == callbackQuery.getFrom().getId();
    answerCallback(bot, callbackQuery, request == null ? "Уже скачиваю или ссылка устарела"
        : own ? null : "Скачивание запускает тот, кто отправил ссылку");
    if (own && callbackQuery.getInlineMessageId() != null && pending.remove(key, request)) {
      download(bot, callbackQuery.getInlineMessageId(), callbackQuery.getFrom(), request.link());
    }
  }

  private void download(AbsSender bot, String inlineMessageId, User user, LinkRequest link) {
    UserInfo userInfo = userService.updateOrCreate(UserInfoDto.builder()
        .telegramUserId(user.getId())
        .username(user.getUserName())
        .firstName(user.getFirstName())
        .lastName(user.getLastName())
        .build());
    MediaType mediaType = videoDownloadService.getMediaType(link.url());
    OutputFormat format = mediaType == MediaType.AUDIO ? OutputFormat.MP3
        : userInfo.getOutputFormat();
    Quality quality = userInfo.getQuality();
    RequestLog requestLog = requestService.saveRequest(userInfo,
        link.crop() == null ? RequestType.DOWNLOAD : RequestType.CROP,
        link.url() + (link.crop() == null ? "" : " " + link.crop().start().source() + " "
            + link.crop().end().source()) + " [inline]", quality, format);

    File source = null;
    File result = null;
    try {
      source = videoDownloadService.downloadVideo(link.url(),
          DownloadOptions.of(quality, format, link.crop() != null, downloadConfiguration));
      result = mediaProcessingService.process(source, format, quality, link.crop());
      long size = Files.size(result.toPath());
      if (size > TELEGRAM_UPLOAD_LIMIT) {
        throw new UserFacingException("Файл получился больше 50 МБ. Выберите качество пониже в "
            + "настройках бота или вырежьте фрагмент");
      }
      bot.execute(EditMessageMedia.builder()
          .inlineMessageId(inlineMessageId)
          .media(media(upload(bot, result, format)))
          .build());
      requestService.markDownloaded(requestLog, size);
    } catch (UserFacingException e) {
      requestService.markFailed(requestLog, e.getMessage());
      editCaption(bot, inlineMessageId, "⚠️ " + e.getMessage());
    } catch (Exception e) {
      log.error("Ошибка инлайн-скачивания {}: {}", link.url(), e.getMessage(), e);
      requestService.markFailed(requestLog, e.getClass().getSimpleName() + ": " + e.getMessage());
      editCaption(bot, inlineMessageId, "⚠️ Не получилось скачать видео. Попробуйте ещё раз");
    } finally {
      cleanup(result);
      if (source != result) {
        cleanup(source);
      }
    }
  }

  /**
   * Загружает файл в служебный чат, удаляет сообщение и возвращает file_id.
   */
  private Uploaded upload(AbsSender bot, File file, OutputFormat format)
      throws TelegramApiException {
    String chatId = botConfig.getEffectiveStorageChatId();
    InputFile inputFile = new InputFile(file);
    Message message = switch (format) {
      case MP4 -> {
        MediaProcessingService.VideoInfo info = mediaProcessingService.videoInfo(file);
        yield bot.execute(SendVideo.builder().chatId(chatId).video(inputFile)
            .supportsStreaming(true)
            .width(info == null ? null : info.width())
            .height(info == null ? null : info.height())
            .duration(info == null ? null : info.durationSeconds())
            .disableNotification(true).build());
      }
      case MP3 -> {
        MediaProcessingService.AudioTags tags = mediaProcessingService.readAudioTags(file);
        yield bot.execute(SendAudio.builder().chatId(chatId).audio(inputFile)
            .performer(tags.performer()).title(tags.title())
            .disableNotification(true).build());
      }
      case WEBM, WEBP -> bot.execute(SendDocument.builder().chatId(chatId).document(inputFile)
          .disableNotification(true).build());
    };
    deleteQuietly(bot, message);
    return Uploaded.of(message);
  }

  private enum Kind { VIDEO, ANIMATION, AUDIO, DOCUMENT }

  /**
   * Загруженный файл. Telegram сам решает, чем его считать: например, видео без звука он
   * сохраняет как GIF-анимацию, поэтому тип берём из ответа, а не из того, как отправляли.
   */
  private record Uploaded(String fileId, Kind kind) {

    static Uploaded of(Message message) {
      if (message.getVideo() != null) {
        return new Uploaded(message.getVideo().getFileId(), Kind.VIDEO);
      }
      if (message.getAnimation() != null) {
        return new Uploaded(message.getAnimation().getFileId(), Kind.ANIMATION);
      }
      if (message.getAudio() != null) {
        return new Uploaded(message.getAudio().getFileId(), Kind.AUDIO);
      }
      if (message.getDocument() != null) {
        return new Uploaded(message.getDocument().getFileId(), Kind.DOCUMENT);
      }
      throw new IllegalStateException("Telegram не вернул загруженный файл");
    }
  }

  private InputMedia media(Uploaded uploaded) {
    InputMedia media = switch (uploaded.kind()) {
      case VIDEO -> {
        InputMediaVideo video = new InputMediaVideo();
        video.setSupportsStreaming(true);
        yield video;
      }
      case ANIMATION -> new InputMediaAnimation();
      case AUDIO -> new InputMediaAudio();
      case DOCUMENT -> new InputMediaDocument();
    };
    media.setMedia(uploaded.fileId());
    return media;
  }

  /**
   * file_id маленького видео-заглушки: инлайн-сообщение должно быть медиа, чтобы его потом можно
   * было заменить на видео.
   */
  private String placeholder(AbsSender bot) {
    if (placeholderFileId != null) {
      return placeholderFileId;
    }
    synchronized (this) {
      if (placeholderFileId != null) {
        return placeholderFileId;
      }
      String chatId = botConfig.getEffectiveStorageChatId();
      if (chatId == null) {
        log.warn("Инлайн-режим недоступен: не задан TELEGRAM_ADMIN_ID или "
            + "TELEGRAM_STORAGE_CHAT_ID");
        return null;
      }
      Path file = null;
      try {
        file = Files.createTempFile("placeholder_", ".mp4");
        // Тихая звуковая дорожка обязательна: видео без звука Telegram превращает в GIF,
        // а инлайн-результат должен быть именно видео
        Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=0x1f1f1f:s=320x180:d=1:r=1",
            "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
            file.toString())
            .inheritIO().start();
        if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
          throw new IllegalStateException("ffmpeg не создал заглушку");
        }
        Message message = bot.execute(SendVideo.builder()
            .chatId(chatId)
            .video(new InputFile(file.toFile(), "loading.mp4"))
            .disableNotification(true)
            .build());
        deleteQuietly(bot, message);
        if (message.getVideo() == null) {
          throw new IllegalStateException("Telegram сохранил заглушку не как видео");
        }
        placeholderFileId = message.getVideo().getFileId();
        log.info("Заглушка для инлайн-режима готова");
      } catch (Exception e) {
        log.error("Не удалось подготовить заглушку для инлайн-режима: {}", e.getMessage(), e);
      } finally {
        if (file != null) {
          file.toFile().delete();
        }
      }
      return placeholderFileId;
    }
  }

  private void answer(AbsSender bot, InlineQuery query, List<InlineQueryResult> results,
      String hint) throws TelegramApiException {
    AnswerInlineQuery.AnswerInlineQueryBuilder answer = AnswerInlineQuery.builder()
        .inlineQueryId(query.getId())
        .results(results)
        .cacheTime(0)
        .isPersonal(true);
    if (hint != null) {
      // Кнопка над результатами открывает личку с ботом
      answer.button(InlineQueryResultsButton.builder()
          .text(hint)
          .startParameter("inline")
          .build());
    }
    bot.execute(answer.build());
  }

  private void editCaption(AbsSender bot, String inlineMessageId, String text) {
    try {
      bot.execute(EditMessageCaption.builder()
          .inlineMessageId(inlineMessageId)
          .caption(text)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Не удалось обновить инлайн-сообщение: {}", e.getMessage());
    }
  }

  private void answerCallback(AbsSender bot, CallbackQuery callbackQuery, String text) {
    try {
      bot.execute(AnswerCallbackQuery.builder()
          .callbackQueryId(callbackQuery.getId())
          .text(text)
          .build());
    } catch (TelegramApiException e) {
      log.debug("Не удалось ответить на callback: {}", e.getMessage());
    }
  }

  private void deleteQuietly(AbsSender bot, Message message) {
    try {
      bot.execute(DeleteMessage.builder()
          .chatId(message.getChatId().toString())
          .messageId(message.getMessageId())
          .build());
    } catch (TelegramApiException e) {
      log.warn("Не удалось удалить служебное сообщение: {}", e.getMessage());
    }
  }

  private void removeExpired() {
    Instant border = Instant.now().minus(PENDING_TTL);
    pending.values().removeIf(request -> request.createdAt().isBefore(border));
  }

  private void cleanup(File file) {
    if (file != null && file.getParentFile() != null
        && file.getParentFile().getName().matches("(ytdlp|media|yandex_track)_.*")) {
      YtDlpClient.deleteDirectory(file.getParentFile().toPath());
    } else if (file != null) {
      file.delete();
    }
  }
}
