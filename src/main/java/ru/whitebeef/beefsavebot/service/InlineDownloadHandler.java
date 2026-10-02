package ru.whitebeef.beefsavebot.service;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerInlineQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.inlinequery.ChosenInlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResult;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultsButton;
import org.telegram.telegrambots.meta.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle;
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
import ru.whitebeef.beefsavebot.service.cache.CachedMedia;
import ru.whitebeef.beefsavebot.service.cache.MediaCacheService;
import ru.whitebeef.beefsavebot.service.download.MediaType;
import ru.whitebeef.beefsavebot.service.download.VideoDownloadService;
import ru.whitebeef.beefsavebot.service.download.YtDlpClient;
import ru.whitebeef.beefsavebot.service.media.LinkRequest;
import ru.whitebeef.beefsavebot.service.media.MediaProcessingService;
import ru.whitebeef.beefsavebot.service.media.MediaSender;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Инлайн-режим: в любом чате пишем {@code @бот ссылка [начало конец]}, выбираем подсказку — в
 * чат уходит текстовая заглушка «Скачиваю…», которую бот потом заменяет на видео.
 * <p>
 * Заглушка именно текстовая: видео-результат на телефонах сначала открывается в превью, и
 * отправлять приходится двумя нажатиями, а до замены в чате висит пустой чёрный ролик.
 * <p>
 * В инлайн-сообщение нельзя загрузить новый файл, можно только подставить уже загруженный по
 * file_id. Поэтому бот загружает результат в служебный чат, берёт file_id, удаляет служебное
 * сообщение и редактирует инлайн-сообщение. Файл больше 50 МБ в одно сообщение не влезет —
 * его части бот присылает в личку.
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

  private final BotConfiguration botConfig;
  private final DownloadConfiguration downloadConfiguration;
  private final VideoDownloadService videoDownloadService;
  private final MediaProcessingService mediaProcessingService;
  private final UserService userService;
  private final RequestService requestService;
  private final MediaCacheService mediaCacheService;
  private final MediaSender mediaSender;

  /**
   * Подсказки, выбранные пользователем, но ещё не скачанные: ключ — id результата.
   */
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

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
    if (botConfig.getEffectiveStorageChatId() == null) {
      log.warn("Инлайн-режим недоступен: не задан TELEGRAM_ADMIN_ID или "
          + "TELEGRAM_STORAGE_CHAT_ID");
      answer(bot, query, List.of(), "Инлайн-режим не настроен");
      return;
    }

    removeExpired();
    String key = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    pending.put(key, new Pending(link, query.getFrom().getId(), Instant.now()));
    String title = "📥 Скачать " + (link.crop() == null ? "видео" : "фрагмент " + link.crop());
    // Кнопка обязательна: без клавиатуры Telegram не сообщает id инлайн-сообщения
    InlineQueryResultArticle result = InlineQueryResultArticle.builder()
        .id(key)
        .title(title)
        .description(link.url())
        .inputMessageContent(InputTextMessageContent.builder()
            .messageText("⏳ Скачиваю…")
            .disableWebPagePreview(true)
            .build())
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
        editText(bot, chosen.getInlineMessageId(), "⚠️ " + e.getMessage());
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

    String cacheKey = MediaCacheService.key(link.url(), format, quality, link.crop());
    Optional<CachedMedia> cached = mediaCacheService.find(cacheKey);
    if (cached.isPresent()) {
      try {
        bot.execute(EditMessageMedia.builder()
            .inlineMessageId(inlineMessageId)
            .media(cached.get().toInputMedia())
            .build());
        requestService.markDownloaded(requestLog, cached.get().fileSize() == null ? 0
            : cached.get().fileSize());
        return;
      } catch (TelegramApiException e) {
        log.warn("Файл из кэша не отправился, качаю заново: {}", e.getMessage());
        mediaCacheService.evict(cacheKey);
      }
    }

    File source = null;
    File result = null;
    try {
      source = videoDownloadService.downloadVideo(link.url(),
          DownloadOptions.of(quality, format, link.crop() != null, downloadConfiguration));
      result = mediaProcessingService.process(source, format, quality, link.crop());
      long size = Files.size(result.toPath());
      if (size > MediaSender.TELEGRAM_UPLOAD_LIMIT) {
        sendPartsToPrivateChat(bot, inlineMessageId, user, result, format);
        requestService.markDownloaded(requestLog, size);
        return;
      }
      CachedMedia uploaded = upload(bot, result, format);
      mediaCacheService.put(cacheKey, uploaded);
      bot.execute(EditMessageMedia.builder()
          .inlineMessageId(inlineMessageId)
          .media(uploaded.toInputMedia())
          .build());
      requestService.markDownloaded(requestLog, size);
    } catch (UserFacingException e) {
      requestService.markFailed(requestLog, e.getMessage());
      editText(bot, inlineMessageId, "⚠️ " + e.getMessage());
    } catch (Exception e) {
      log.error("Ошибка инлайн-скачивания {}: {}", link.url(), e.getMessage(), e);
      requestService.markFailed(requestLog, e.getClass().getSimpleName() + ": " + e.getMessage());
      editText(bot, inlineMessageId, "⚠️ Не получилось скачать видео. Попробуйте ещё раз");
    } finally {
      cleanup(result);
      if (source != result) {
        cleanup(source);
      }
    }
  }

  /**
   * Файл больше 50 МБ: инлайн-сообщение может показать только один файл, поэтому части уходят
   * в личку с ботом. Писать первым бот может только тем, кто его уже запускал.
   */
  private void sendPartsToPrivateChat(AbsSender bot, String inlineMessageId, User user,
      File file, OutputFormat format) throws Exception {
    editText(bot, inlineMessageId, "⏳ Видео больше 50 МБ — режу на части и отправляю в личку "
        + "с ботом…");
    try {
      mediaSender.send(bot, user.getId().toString(), null, file, format, false);
    } catch (TelegramApiException e) {
      log.warn("Не удалось отправить части в личку {}: {}", user.getId(), e.getMessage());
      throw new UserFacingException("Видео больше 50 МБ, его можно прислать только частями в "
          + "личку. Запустите бота (/start) в личных сообщениях и отправьте ссылку туда");
    }
    editText(bot, inlineMessageId, "📨 Видео больше 50 МБ — отправил его частями в личку с "
        + "ботом");
  }

  /**
   * Загружает файл в служебный чат, удаляет сообщение и возвращает file_id.
   */
  private CachedMedia upload(AbsSender bot, File file, OutputFormat format) throws Exception {
    Message message = mediaSender.send(bot, botConfig.getEffectiveStorageChatId(), null, file,
        format, true).get(0);
    deleteQuietly(bot, message);
    CachedMedia uploaded = CachedMedia.of(message);
    if (uploaded == null) {
      throw new IllegalStateException("Telegram не вернул загруженный файл");
    }
    return uploaded;
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

  private void editText(AbsSender bot, String inlineMessageId, String text) {
    try {
      bot.execute(EditMessageText.builder()
          .inlineMessageId(inlineMessageId)
          .text(text)
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
