package ru.whitebeef.beefsavebot.service;


import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Audio;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Video;
import org.telegram.telegrambots.meta.api.objects.VideoNote;
import org.telegram.telegrambots.meta.api.objects.Voice;
import org.telegram.telegrambots.meta.api.objects.games.Animation;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.whitebeef.beefsavebot.configuration.BotConfiguration;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.dto.Screen;
import ru.whitebeef.beefsavebot.dto.UserInfoDto;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.model.RequestType;
import ru.whitebeef.beefsavebot.service.admin.AdminPanel;
import ru.whitebeef.beefsavebot.service.admin.AdminService;
import ru.whitebeef.beefsavebot.service.convert.ConversionResult;
import ru.whitebeef.beefsavebot.service.convert.ConversionService;
import ru.whitebeef.beefsavebot.service.convert.ConversionSession;
import ru.whitebeef.beefsavebot.service.convert.Formats;
import ru.whitebeef.beefsavebot.service.download.DrmProtectedException;
import ru.whitebeef.beefsavebot.service.download.MediaType;
import ru.whitebeef.beefsavebot.service.download.VideoDownloadService;
import ru.whitebeef.beefsavebot.service.download.YandexMusicDownloadService;
import ru.whitebeef.beefsavebot.service.media.CropRange;
import ru.whitebeef.beefsavebot.service.music.MusicSearchService;
import ru.whitebeef.beefsavebot.service.music.TrackResult;
import ru.whitebeef.beefsavebot.service.media.LinkRequest;
import ru.whitebeef.beefsavebot.service.media.MediaProcessingService;
import ru.whitebeef.beefsavebot.service.media.TimeCode;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;
import ru.whitebeef.beefsavebot.util.Html;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService extends TelegramLongPollingBot {

  private static final String TRACK_CALLBACK_PREFIX = "ym_track:";
  private static final String QUALITY_CALLBACK_PREFIX = "set:q:";
  private static final String FORMAT_CALLBACK_PREFIX = "set:f:";
  private static final String MUSIC_PROVIDER_CALLBACK_PREFIX = "set:m:";
  private static final String MUSIC_TRACK_CALLBACK_PREFIX = "mus:";
  private static final String MUSIC_SEARCH_ALL_CALLBACK_PREFIX = "msrch:";
  private static final int BUTTON_TEXT_LIMIT = 64;
  private static final int MESSAGE_LIMIT = 4000;
  private static final Duration LAST_LINK_TTL = Duration.ofHours(6);
  /**
   * Ограничение Bot API на отправку файлов.
   */
  private static final long TELEGRAM_UPLOAD_LIMIT = 50L * 1024 * 1024;
  /**
   * Ограничение Bot API на скачивание файлов ботом.
   */
  private static final long TELEGRAM_DOWNLOAD_LIMIT = 20L * 1024 * 1024;

  private final BotConfiguration botConfig;
  private final DownloadConfiguration downloadConfiguration;
  private final VideoDownloadService videoDownloadService;
  private final YandexMusicDownloadService yandexMusicDownloadService;
  private final MediaProcessingService mediaProcessingService;
  private final RequestService requestService;
  private final UserService userService;
  private final AdminService adminService;
  private final AdminPanel adminPanel;
  private final ConversionService conversionService;
  private final InlineDownloadHandler inlineDownloadHandler;
  private final MusicSearchService musicSearchService;
  /**
   * Последняя ссылка на видео в каждом групповом чате.
   */
  private final Map<Long, LastLink> lastLinks = new ConcurrentHashMap<>();
  private final ExecutorService executorService = Executors.newFixedThreadPool(10);

  @PostConstruct
  public void registerBot() throws Exception {
    TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
    try {
      api.registerBot(this);
    } catch (Exception exception) {
      log.error("Не удалось зарегистрировать бота, проверьте token и username в конфигурации");
      return;
    }
    registerCommands();
  }

  private void registerCommands() {
    List<BotCommand> userCommands = List.of(
        new BotCommand("start", "Что умеет бот"),
        new BotCommand("settings", "Качество и формат"),
        new BotCommand("crop", "Обрезать видео: /crop ссылка начало конец"),
        new BotCommand("save", "Скачать видео по ссылке из сообщения, на которое отвечаете"),
        new BotCommand("convert", "Конвертер файлов"),
        new BotCommand("help", "Помощь"));
    try {
      execute(SetMyCommands.builder()
          .commands(userCommands)
          .scope(new BotCommandScopeDefault())
          .build());
      String adminId = botConfig.getAdminId();
      if (adminId != null && !adminId.isBlank()) {
        List<BotCommand> adminCommands = new ArrayList<>(userCommands);
        adminCommands.addAll(List.of(
            new BotCommand("admin", "Админка"),
            new BotCommand("stats", "Статистика"),
            new BotCommand("requests", "Все запросы"),
            new BotCommand("byusers", "Запросы по пользователям"),
            new BotCommand("find", "Поиск по запросам"),
            new BotCommand("errors", "Последние ошибки"),
            new BotCommand("users", "Пользователи"),
            new BotCommand("user", "Карточка пользователя"),
            new BotCommand("ban", "Заблокировать пользователя"),
            new BotCommand("unban", "Разблокировать пользователя"),
            new BotCommand("send", "Написать пользователю"),
            new BotCommand("broadcast", "Рассылка всем"),
            new BotCommand("export", "Выгрузка в CSV")));
        execute(SetMyCommands.builder()
            .commands(adminCommands)
            .scope(BotCommandScopeChat.builder().chatId(adminId.trim()).build())
            .build());
      }
    } catch (TelegramApiException e) {
      log.warn("Не удалось зарегистрировать меню команд: {}", e.getMessage());
    }
  }

  @Override
  public void onUpdateReceived(Update update) {
    if (update.hasInlineQuery()) {
      executorService.execute(() -> {
        try {
          inlineDownloadHandler.handleQuery(this, update.getInlineQuery());
        } catch (Exception e) {
          log.error("Ошибка инлайн-запроса '{}': {}", update.getInlineQuery().getQuery(),
              e.getMessage(), e);
        }
      });
      return;
    }
    if (update.hasChosenInlineQuery()) {
      executorService.execute(() -> inlineDownloadHandler.handleChosen(this,
          update.getChosenInlineQuery()));
      return;
    }
    if (update.hasCallbackQuery()) {
      executorService.execute(() -> this.executeCallback(update));
      return;
    }
    if (!update.hasMessage()) {
      return;
    }
    Message message = update.getMessage();
    // Файлы конвертируем только в личке, чтобы не отвечать на каждую картинку в группах
    if (message.isUserMessage() && incomingFile(message) != null) {
      executorService.execute(() -> this.executeFile(update));
      return;
    }
    if (!message.hasText()) {
      return;
    }
    executorService.execute(() -> this.executeUpdate(update));
  }

  public void executeUpdate(Update update) {
    Message message = update.getMessage();
    String text = message.getText().trim();
    Long chatId = message.getChatId();
    try {
      UserInfo userInfo = userService.updateOrCreate(toDto(message.getFrom()));
      boolean admin = botConfig.isAdmin(userInfo.getTelegramUserId());
      if (Boolean.TRUE.equals(userInfo.getBanned()) && !admin) {
        sendText(chatId, "⛔ Доступ к боту ограничен.");
        return;
      }
      if (text.startsWith("/")) {
        handleCommand(chatId, userInfo, message, text, admin);
        return;
      }
      if (!message.isUserMessage()) {
        handleGroupMessage(chatId, userInfo, message, text);
        return;
      }
      LinkRequest link = parseLink(chatId, text);
      String replySource = replySource(message);
      if (link == null && replySource != null) {
        // Ответ на сообщение со ссылкой: в тексте могут быть только таймкоды
        link = linkFromSource(chatId, replySource, text);
      }
      if (link != null) {
        handleDownload(chatId, userInfo, link.url(), link.crop(), null);
      } else {
        // Ссылок нет — ищем текст как название трека
        handleDownload(chatId, userInfo, text, null);
      }
    } catch (Exception e) {
      log.error("Ошибка при обработке сообщения '{}': {}", text, e.getMessage(), e);
      sendError(chatId);
    }
  }

  /**
   * В группах бот реагирует только на упоминание: «@бот ссылка [начало конец]».
   */
  /**
   * В группах бот реагирует только на упоминание: «@бот ссылка [начало конец]» или «@бот» в ответ
   * на сообщение со ссылкой — тогда видео приходит ответом на это сообщение.
   */
  private void handleGroupMessage(Long chatId, UserInfo userInfo, Message message, String text)
      throws TelegramApiException {
    rememberLink(chatId, message);
    String mention = "@" + getBotUsername();
    if (!text.toLowerCase().contains(mention.toLowerCase())) {
      return;
    }
    LinkRequest link = parseLink(chatId, text);
    Integer replyTo = message.getMessageId();
    String replySource = replySource(message);
    if (link == null && replySource != null) {
      link = linkFromSource(chatId, replySource, text);
      replyTo = replyTargetId(message, replyTo);
    }
    if (link == null) {
      sendText(chatId, "Ответьте командой /save на сообщение со ссылкой — я скачаю видео.\n"
          + "Или напишите ссылку вместе с упоминанием: скачай " + mention + " <ссылка>\n"
          + "Можно сразу вырезать фрагмент: /save 0:10 0:25");
      return;
    }
    if (!videoDownloadService.canDownloadVideo(link.url())) {
      sendText(chatId, "Не умею скачивать по этой ссылке :(");
      return;
    }
    handleDownload(chatId, userInfo, link.url(), link.crop(), replyTo);
  }

  /**
   * «/save» в ответ на сообщение со ссылкой (или «/save ссылка»). В отличие от «@бот» в начале
   * сообщения, команда не включает инлайн-режим Telegram, поэтому её всегда можно отправить.
   */
  private void handleSave(Long chatId, UserInfo userInfo, Message message, String args)
      throws TelegramApiException {
    LinkRequest link = parseLink(chatId, args);
    Integer replyTo = message.isUserMessage() ? null : message.getMessageId();
    String replySource = replySource(message);
    if (link == null && replySource != null) {
      link = linkFromSource(chatId, replySource, args);
      replyTo = replyTargetId(message, replyTo);
    }
    if (link == null) {
      // Ответа нет (или Telegram его не передал) — берём последнюю ссылку, замеченную в чате
      LastLink last = lastLinks.get(chatId);
      if (last != null && last.seenAt().isAfter(Instant.now().minus(LAST_LINK_TTL))) {
        link = parseLink(chatId, last.url() + " " + args);
        replyTo = message.isUserMessage() ? null : last.messageId();
      }
    }
    if (link == null) {
      log.info("/save без ссылки в чате {}: reply={}, quote={}, externalReply={}", chatId,
          message.getReplyToMessage() != null, message.getQuote() != null,
          message.getExternalReplyInfo() != null);
      sendText(chatId, "Ответьте командой /save на сообщение со ссылкой на видео "
          + "или напишите /save <ссылка>.\nМожно сразу вырезать фрагмент: /save 0:10 0:25");
      return;
    }
    if (!videoDownloadService.canDownloadVideo(link.url())) {
      sendText(chatId, "Не умею скачивать по этой ссылке :(");
      return;
    }
    handleDownload(chatId, userInfo, link.url(), link.crop(), replyTo);
  }

  /**
   * Ссылка из сообщения, на которое ответили; таймкоды берутся из текста ответа
   * («@бот 0:10 0:25»). {@code null}, если ссылки там нет.
   */
  private LinkRequest linkFromSource(Long chatId, String source, String text)
      throws TelegramApiException {
    LinkRequest replyLink = parseLink(chatId, source);
    if (replyLink == null) {
      return null;
    }
    String timeCodes = text.replaceAll("@\\S+", " ").trim();
    return parseLink(chatId, replyLink.url() + " " + timeCodes);
  }

  private record LastLink(String url, Integer messageId, Instant seenAt) {

  }

  /**
   * Запоминает последнюю ссылку в групповом чате: «/save» без ответа скачает её. Бот видит
   * обычные сообщения группы, только если в BotFather выключен режим приватности.
   */
  private void rememberLink(Long chatId, Message message) {
    try {
      LinkRequest link = LinkRequest.parse(linkSource(message));
      if (link != null && videoDownloadService.canDownloadVideo(link.url())) {
        lastLinks.put(chatId, new LastLink(link.url(), message.getMessageId(), Instant.now()));
      }
    } catch (UserFacingException ignored) {
      // Кривые таймкоды в чужом сообщении нас не интересуют
    }
  }

  /**
   * Всё, откуда можно достать ссылку, если сообщение — ответ: само исходное сообщение, цитата из
   * него или ответ на сообщение из другого чата. {@code null}, если это не ответ.
   */
  static String replySource(Message message) {
    StringBuilder source = new StringBuilder();
    if (message.getReplyToMessage() != null) {
      source.append(linkSource(message.getReplyToMessage())).append(' ');
    }
    if (message.getQuote() != null && message.getQuote().getText() != null) {
      Message quote = new Message();
      quote.setText(message.getQuote().getText());
      quote.setEntities(message.getQuote().getEntities());
      source.append(linkSource(quote)).append(' ');
    }
    if (message.getExternalReplyInfo() != null
        && message.getExternalReplyInfo().getLinkPreviewOptions() != null
        && message.getExternalReplyInfo().getLinkPreviewOptions().getUrlField() != null) {
      source.append(message.getExternalReplyInfo().getLinkPreviewOptions().getUrlField());
    }
    return source.toString().isBlank() ? null : source.toString();
  }

  private static Integer replyTargetId(Message message, Integer fallback) {
    return message.getReplyToMessage() != null ? message.getReplyToMessage().getMessageId()
        : fallback;
  }

  /**
   * Текст сообщения вместе со ссылками, спрятанными под словами (text_link), и подписью к медиа.
   */
  static String linkSource(Message message) {
    StringBuilder source = new StringBuilder();
    if (message.getLinkPreviewOptions() != null
        && message.getLinkPreviewOptions().getUrlField() != null) {
      source.append(message.getLinkPreviewOptions().getUrlField()).append(' ');
    }
    List<MessageEntity> entities = new ArrayList<>();
    if (message.getText() != null) {
      source.append(message.getText());
      if (message.getEntities() != null) {
        entities.addAll(message.getEntities());
      }
    }
    if (message.getCaption() != null) {
      source.append(' ').append(message.getCaption());
      if (message.getCaptionEntities() != null) {
        entities.addAll(message.getCaptionEntities());
      }
    }
    // Ссылки под текстом ставим в начало: у них приоритет над упоминаниями в тексте
    StringBuilder hidden = new StringBuilder();
    for (MessageEntity entity : entities) {
      if ("text_link".equals(entity.getType()) && entity.getUrl() != null) {
        hidden.append(entity.getUrl()).append(' ');
      }
    }
    return hidden.toString() + source;
  }

  /**
   * Ссылка из текста; об ошибке в таймкодах сообщает пользователю и возвращает {@code null}.
   */
  private LinkRequest parseLink(Long chatId, String text) throws TelegramApiException {
    try {
      return LinkRequest.parse(text);
    } catch (UserFacingException e) {
      sendHtml(chatId, "⚠️ " + Html.escape(e.getMessage()) + "\n\n" + cropHelpText(), null);
      return null;
    }
  }

  public void executeCallback(Update update) {
    CallbackQuery callbackQuery = update.getCallbackQuery();
    String data = callbackQuery.getData();
    if (callbackQuery.getMessage() == null) {
      // Кнопки инлайн-сообщений приходят без самого сообщения
      if (data != null && data.startsWith(InlineDownloadHandler.CALLBACK_PREFIX)) {
        inlineDownloadHandler.handleCallback(this, callbackQuery);
      } else {
        answerCallback(callbackQuery, null);
      }
      return;
    }
    Long chatId = callbackQuery.getMessage().getChatId();
    if (data == null) {
      answerCallback(callbackQuery, null);
      return;
    }
    try {
      UserInfo userInfo = userService.updateOrCreate(toDto(callbackQuery.getFrom()));
      boolean admin = botConfig.isAdmin(userInfo.getTelegramUserId());
      if (Boolean.TRUE.equals(userInfo.getBanned()) && !admin) {
        answerCallback(callbackQuery, "⛔ Доступ к боту ограничен.");
        return;
      }
      if (data.startsWith(TRACK_CALLBACK_PREFIX)) {
        answerCallback(callbackQuery, null);
        handleTrackCallback(chatId, userInfo, data.substring(TRACK_CALLBACK_PREFIX.length()));
      } else if (data.startsWith(MUSIC_TRACK_CALLBACK_PREFIX)) {
        answerCallback(callbackQuery, null);
        handleMusicTrack(chatId, userInfo,
            data.substring(MUSIC_TRACK_CALLBACK_PREFIX.length()));
      } else if (data.startsWith(MUSIC_SEARCH_ALL_CALLBACK_PREFIX)) {
        handleMusicSearchEverywhere(callbackQuery, userInfo,
            data.substring(MUSIC_SEARCH_ALL_CALLBACK_PREFIX.length()));
      } else if (data.startsWith(QUALITY_CALLBACK_PREFIX) || data.startsWith(FORMAT_CALLBACK_PREFIX)
          || data.startsWith(MUSIC_PROVIDER_CALLBACK_PREFIX)) {
        handleSettingsCallback(callbackQuery, userInfo, data);
      } else if (data.startsWith(ConversionService.CALLBACK_PREFIX)) {
        handleConversionCallback(callbackQuery, userInfo,
            data.substring(ConversionService.CALLBACK_PREFIX.length()));
      } else if (data.startsWith(AdminPanel.CALLBACK_PREFIX) && admin) {
        handleAdminCallback(callbackQuery, data.substring(AdminPanel.CALLBACK_PREFIX.length()));
      } else {
        answerCallback(callbackQuery, null);
      }
    } catch (Exception e) {
      log.error("Ошибка при обработке callback '{}': {}", data, e.getMessage(), e);
      sendError(chatId);
    }
  }

  // ---------------------------------------------------------------- команды

  private void handleCommand(Long chatId, UserInfo userInfo, Message message, String text,
      boolean admin) throws TelegramApiException {
    String[] parts = text.split("\\s+", 2);
    String command = parts[0].toLowerCase();
    int botNameIndex = command.indexOf('@');
    if (botNameIndex > 0) {
      command = command.substring(0, botNameIndex);
    }
    String args = parts.length > 1 ? parts[1].trim() : "";

    if (admin && handleAdminCommand(chatId, command, args)) {
      return;
    }
    switch (command) {
      case "/start", "/help" -> {
        requestService.saveRequest(userInfo, RequestType.COMMAND, text, null, null);
        sendHtml(chatId, welcomeText(userInfo), null);
      }
      case "/settings" -> {
        requestService.saveRequest(userInfo, RequestType.COMMAND, text, null, null);
        sendHtml(chatId, settingsText(userInfo), settingsKeyboard(userInfo));
      }
      case "/crop" -> handleCrop(chatId, userInfo, args);
      case "/save", "/dl" -> handleSave(chatId, userInfo, message, args);
      case "/convert" -> {
        requestService.saveRequest(userInfo, RequestType.COMMAND, text, null, null);
        sendHtml(chatId, "🔄 <b>Конвертер</b>\n\nПришлите файл (до 20 МБ) или архив с файлами — "
            + "я предложу, во что его превратить. Для архива можно выбрать свой формат для "
            + "каждого типа файлов внутри, результат придёт архивом.\n\n"
            + conversionService.supportedFormatsText(), null);
      }
      default -> {
        requestService.saveRequest(userInfo, RequestType.COMMAND, text, null, null);
        sendText(chatId, "Не знаю такой команды 🤔 Список команд — /help");
      }
    }
  }

  private String welcomeText(UserInfo userInfo) {
    String name = userInfo.getFirstName() == null || userInfo.getFirstName().isBlank()
        ? "" : ", " + Html.escape(userInfo.getFirstName());
    String author = Html.escape(botConfig.getAuthor());
    return "👋 Привет" + name + "!\n\n"
        + "Я <b>BeefSaveBot</b> — скачиваю видео и музыку. Просто пришли ссылку, "
        + "а я пришлю файл.\n\n"
        + "<b>Откуда умею скачивать:</b>\n"
        + Html.escape(videoDownloadService.getSupportedSites()) + "\n\n"
        + "<b>Что ещё умею:</b>\n"
        + "🎵 Искать треки в Яндекс Музыке, SoundCloud и YouTube Music — просто напиши "
        + "название песни "
        + "(где искать сначала — в /settings)\n"
        + "⚙️ Присылать файл в нужном формате (MP4, MP3, WEBM, WEBP) и качестве — /settings\n"
        + "✂️ Вырезать фрагмент видео с точностью до кадра — /crop\n"
        + "🔄 Конвертировать файлы и целые архивы: картинки, видео, аудио, документы, таблицы, "
        + "презентации, данные — просто пришли файл (подробнее — /convert)\n\n"
        + "<b>Команды:</b>\n"
        + "/settings — качество и формат\n"
        + "/crop &lt;ссылка&gt; &lt;начало&gt; &lt;конец&gt; — обрезать видео\n"
        + "/convert — какие форматы умею конвертировать\n"
        + "/help — это сообщение\n\n"
        + "<b>В любом чате:</b> напиши <code>@" + Html.escape(getBotUsername())
        + " ссылка</code> и выбери подсказку — видео отправится прямо в этот чат. "
        + "В группах, где есть бот, ответьте командой /save на сообщение со ссылкой — видео "
        + "придёт ответом на него. Или упомяните бота со ссылкой в тексте сообщения. "
        + "После ссылки можно указать начало и конец фрагмента: <code>ссылка 0:10 0:25</code>\n\n"
        + "👨‍💻 Автор: " + author + "\n\n"
        + "💚 Бот работает на безвозмездной основе — без рекламы и платных подписок. "
        + "Если он тебе понравился, в благодарность принимаю подарки в Telegram 🎁 → " + author;
  }

  // ---------------------------------------------------------------- настройки

  private String settingsText(UserInfo userInfo) {
    return "⚙️ <b>Настройки вывода</b>\n\n"
        + "Качество: <b>" + userInfo.getQuality().getTitle() + "</b>\n"
        + "Формат: <b>" + userInfo.getOutputFormat().getTitle() + "</b> — "
        + userInfo.getOutputFormat().getDescription() + "\n\n"
        + "<b>Форматы:</b>\n"
        + "MP4 — видео со звуком, открывается везде\n"
        + "MP3 — только звук\n"
        + "WEBM — видео VP9/Opus\n"
        + "WEBP — анимация без звука, как гифка\n\n"
        + "Музыка: <b>" + userInfo.getMusicProvider().getTitle() + "</b> — где сначала искать "
        + "треки по названию. Если там не найдётся, поищу в остальных.\n\n"
        + "<i>Музыка всегда приходит в MP3, качество влияет на битрейт.</i>";
  }

  private InlineKeyboardMarkup settingsKeyboard(UserInfo userInfo) {
    List<InlineKeyboardButton> qualityRow = new ArrayList<>();
    for (Quality quality : Quality.values()) {
      qualityRow.add(InlineKeyboardButton.builder()
          .text((quality == userInfo.getQuality() ? "✅ " : "") + quality.getTitle())
          .callbackData(QUALITY_CALLBACK_PREFIX + quality.name())
          .build());
    }
    List<InlineKeyboardButton> formatRow = new ArrayList<>();
    for (OutputFormat format : OutputFormat.values()) {
      formatRow.add(InlineKeyboardButton.builder()
          .text((format == userInfo.getOutputFormat() ? "✅ " : "") + format.getTitle())
          .callbackData(FORMAT_CALLBACK_PREFIX + format.name())
          .build());
    }
    List<InlineKeyboardButton> musicRow = new ArrayList<>();
    for (MusicProvider provider : musicSearchService.availableProviders()) {
      musicRow.add(InlineKeyboardButton.builder()
          .text((provider == userInfo.getMusicProvider() ? "✅ " : provider.getEmoji() + " ")
              + provider.getShortTitle())
          .callbackData(MUSIC_PROVIDER_CALLBACK_PREFIX + provider.name())
          .build());
    }
    List<List<InlineKeyboardButton>> rows = new ArrayList<>(List.of(qualityRow, formatRow));
    if (musicRow.size() > 1) {
      rows.add(musicRow);
    }
    return InlineKeyboardMarkup.builder().keyboard(rows).build();
  }

  private void handleSettingsCallback(CallbackQuery callbackQuery, UserInfo userInfo, String data)
      throws TelegramApiException {
    Quality quality = data.startsWith(QUALITY_CALLBACK_PREFIX)
        ? Quality.parse(data.substring(QUALITY_CALLBACK_PREFIX.length())) : null;
    OutputFormat format = data.startsWith(FORMAT_CALLBACK_PREFIX)
        ? OutputFormat.parse(data.substring(FORMAT_CALLBACK_PREFIX.length())) : null;
    MusicProvider musicProvider = data.startsWith(MUSIC_PROVIDER_CALLBACK_PREFIX)
        ? MusicProvider.parse(data.substring(MUSIC_PROVIDER_CALLBACK_PREFIX.length())) : null;
    if ((quality == null && format == null && musicProvider == null)
        || quality == userInfo.getQuality() || format == userInfo.getOutputFormat()
        || musicProvider == userInfo.getMusicProvider()) {
      answerCallback(callbackQuery, null);
      return;
    }
    UserInfo updated = userService.updateSettings(userInfo.getTelegramUserId(), quality, format,
        musicProvider);
    answerCallback(callbackQuery, "Сохранено ✅");
    try {
      execute(EditMessageText.builder()
          .chatId(callbackQuery.getMessage().getChatId().toString())
          .messageId(callbackQuery.getMessage().getMessageId())
          .text(settingsText(updated))
          .parseMode(ParseMode.HTML)
          .replyMarkup(settingsKeyboard(updated))
          .build());
    } catch (TelegramApiRequestException e) {
      log.debug("Не удалось обновить сообщение настроек: {}", e.getMessage());
    }
  }

  // ---------------------------------------------------------------- скачивание

  private void handleCrop(Long chatId, UserInfo userInfo, String args)
      throws TelegramApiException {
    String[] tokens = args.isBlank() ? new String[0] : args.split("\\s+");
    String startText;
    String endText;
    if (tokens.length == 3) {
      startText = tokens[1];
      endText = tokens[2];
    } else if (tokens.length == 2 && tokens[1].split("-").length == 2) {
      startText = tokens[1].split("-")[0];
      endText = tokens[1].split("-")[1];
    } else {
      sendHtml(chatId, cropHelpText(), null);
      return;
    }
    CropRange range;
    try {
      range = new CropRange(TimeCode.parse(startText), TimeCode.parse(endText));
    } catch (UserFacingException e) {
      sendHtml(chatId, "⚠️ " + Html.escape(e.getMessage()) + "\n\n" + cropHelpText(), null);
      return;
    }
    if (range.start().frame() == null && range.end().frame() == null
        && range.end().seconds() <= range.start().seconds()) {
      sendText(chatId, "⚠️ Конец фрагмента должен быть позже начала");
      return;
    }
    handleDownload(chatId, userInfo, tokens[0], range);
  }

  private String cropHelpText() {
    return """
        ✂️ <b>Обрезка видео</b>

        Формат: <code>/crop ссылка начало конец</code>

        Таймкоды можно писать так:
        • <code>83</code> или <code>83.5</code> — секунды
        • <code>1:23</code> или <code>1:23.480</code> — минуты:секунды
        • <code>1:02:03</code> — часы:минуты:секунды
        • <code>0:01:23:12</code> — часы:минуты:секунды:<b>кадр</b> (точность до кадра, кадры считаются с 0)

        Примеры:
        <code>/crop https://youtu.be/dQw4w9WgXcQ 0:43 1:05</code>
        <code>/crop https://youtu.be/dQw4w9WgXcQ 0:00:43:12 0:01:05:00</code>

        Результат придёт в формате и качестве из /settings.""";
  }

  private void handleDownload(Long chatId, UserInfo userInfo, String url, CropRange crop)
      throws TelegramApiException {
    handleDownload(chatId, userInfo, url, crop, null);
  }

  /**
   * @param replyTo сообщение, ответом на которое прислать результат, или {@code null}
   */
  private void handleDownload(Long chatId, UserInfo userInfo, String url, CropRange crop,
      Integer replyTo) throws TelegramApiException {
    RequestType requestType = crop == null ? RequestType.DOWNLOAD : RequestType.CROP;
    String requestText = crop == null ? url : url + " " + crop.start().source() + " "
        + crop.end().source();

    if (!videoDownloadService.canDownloadVideo(url)) {
      if (crop == null) {
        requestService.saveRequest(userInfo, RequestType.SEARCH, url, null, null);
        offerSearchResults(chatId, userInfo, url);
      } else {
        RequestLog requestLog = requestService.saveRequest(userInfo, requestType, requestText,
            null, null);
        requestService.markFailed(requestLog, "Неподдерживаемая ссылка");
        sendText(chatId, "Не умею скачивать по этой ссылке :(\nВот сайты, откуда я умею скачивать "
            + "видео:\n\n" + videoDownloadService.getSupportedSites());
      }
      return;
    }

    MediaType mediaType = videoDownloadService.getMediaType(url);
    OutputFormat format = mediaType == MediaType.AUDIO ? OutputFormat.MP3
        : userInfo.getOutputFormat();
    Quality quality = userInfo.getQuality();
    RequestLog requestLog = requestService.saveRequest(userInfo, requestType, requestText,
        quality, format);
    DownloadOptions options = new DownloadOptions(quality, format.isAudioOnly(),
        crop != null || format.isAudioOnly() ? downloadConfiguration.getSourceMaxBytes()
            : downloadConfiguration.getMaxBytes());
    processAndSend(chatId, replyTo, requestLog, mediaType, format, quality, crop, null,
        () -> videoDownloadService.downloadVideo(url, options));
  }

  private void handleTrackCallback(Long chatId, UserInfo userInfo, String trackId) {
    Quality quality = userInfo.getQuality();
    RequestLog requestLog = requestService.saveRequest(userInfo, RequestType.TRACK,
        "yandex-music-search:" + trackId, quality, OutputFormat.MP3);
    processAndSend(chatId, null, requestLog, MediaType.AUDIO, OutputFormat.MP3, quality, null,
        null, () -> yandexMusicDownloadService.downloadTrackById(trackId, quality));
  }

  /**
   * Трек, который при неудаче можно поискать в других сервисах.
   */
  private record MusicFallback(String query, MusicProvider provider) {

  }

  /**
   * @param fallback для музыки: что искать в других сервисах, если скачать не получилось
   */
  private void processAndSend(Long chatId, Integer replyTo, RequestLog requestLog,
      MediaType mediaType, OutputFormat format, Quality quality, CropRange crop,
      MusicFallback fallback, Downloader downloader) {
    File source = null;
    File result = null;
    try {
      String what = mediaType == MediaType.AUDIO ? "трек" : "видео";
      sendHtml(chatId, "⏳ Скачиваю " + what
          + (crop == null ? "" : " и вырезаю фрагмент " + Html.escape(crop.toString()))
          + "… Ожидайте!\n<i>Формат: " + format.getTitle() + ", качество: "
          + quality.getTitle().toLowerCase() + "</i>", null);
      source = downloader.download();
      result = mediaProcessingService.process(source, format, quality, crop);
      long size = Files.size(result.toPath());
      sendMedia(chatId, replyTo, result, format, mediaType);
      requestService.markDownloaded(requestLog, size);
    } catch (UserFacingException e) {
      log.warn("Запрос {} не выполнен: {}", requestLog.getUrl(), e.getMessage());
      requestService.markFailed(requestLog, e.getMessage());
      if (!offerAlternatives(chatId, e, fallback)) {
        trySendText(chatId, "⚠️ " + e.getMessage());
      }
    } catch (Exception e) {
      log.error("Ошибка при обработке {}: {}", requestLog.getUrl(), e.getMessage(), e);
      requestService.markFailed(requestLog, rootMessage(e));
      if (!offerAlternatives(chatId, e, fallback)) {
        sendError(chatId);
      }
    } finally {
      cleanup(result);
      if (source != result) {
        cleanup(source);
      }
    }
  }

  /**
   * Текст без ссылки ищем как название трека: сначала у выбранного поставщика музыки, а если
   * там пусто — у всех остальных.
   */
  /**
   * Трек не скачался из одного сервиса — ищем его в остальных и даём выбрать, откуда скачать.
   *
   * @return {@code true}, если варианты нашлись и сообщение с ними отправлено
   */
  private boolean offerAlternatives(Long chatId, Exception error, MusicFallback fallback) {
    MusicFallback source = fallback;
    if (source == null && error instanceof DrmProtectedException drm
        && drm.getTrackName() != null) {
      source = new MusicFallback(drm.getTrackName(), drm.getProvider());
    }
    if (source == null) {
      return false;
    }
    try {
      List<TrackResult> alternatives = musicSearchService.findAlternatives(source.query(),
          source.provider());
      if (alternatives.isEmpty()) {
        return false;
      }
      String reason = error instanceof UserFacingException ? error.getMessage()
          : "Не получилось скачать трек из "
              + (source.provider() == null ? "этого сервиса" : source.provider().getTitle());
      List<List<InlineKeyboardButton>> keyboard = alternatives.stream()
          .map(track -> List.of(InlineKeyboardButton.builder()
              .text(truncate(track.provider().getEmoji() + " " + track.provider().getShortTitle()
                  + ": " + track.display()))
              .callbackData(MUSIC_TRACK_CALLBACK_PREFIX + musicSearchService.rememberTrack(track))
              .build()))
          .toList();
      execute(SendMessage.builder()
          .chatId(chatId.toString())
          .text("⚠️ " + reason + "\n\nНашёл этот трек в других сервисах — откуда скачать?")
          .replyMarkup(InlineKeyboardMarkup.builder().keyboard(keyboard).build())
          .build());
      return true;
    } catch (Exception e) {
      log.warn("Не удалось предложить другие источники для «{}»: {}", source.query(),
          e.getMessage());
      return false;
    }
  }

  private void offerSearchResults(Long chatId, UserInfo userInfo, String query)
      throws TelegramApiException {
    MusicProvider preferred = userInfo.getMusicProvider();
    MusicSearchService.SearchResults results = musicSearchService.search(query, preferred);
    if (results.tracks().isEmpty()) {
      String where = musicSearchService.availableProviders().stream()
          .map(MusicProvider::getTitle)
          .reduce((a, b) -> a + ", " + b)
          .orElse("музыкальных сервисах");
      sendText(chatId, "Не распознал ссылку, и по названию ничего не нашлось (" + where
          + ").\nВот сайты, откуда я умею скачивать:\n\n"
          + videoDownloadService.getSupportedSites()
          + "\n\nСвяжитесь с " + botConfig.getAuthor()
          + ", если вам необходим какой-то сайт, которого нет в списке :0");
      return;
    }
    String header = results.fromPreferred()
        ? "Не распознал ссылку, но нашёл в " + preferred.getTitle() + ":"
        : "В " + preferred.getTitle() + " ничего не нашлось, вот что есть в других сервисах:";
    List<List<InlineKeyboardButton>> keyboard = new ArrayList<>(trackButtons(results.tracks()));
    boolean othersAvailable = musicSearchService.availableProviders().stream()
        .anyMatch(provider -> provider != preferred);
    if (results.fromPreferred() && othersAvailable) {
      keyboard.add(List.of(InlineKeyboardButton.builder()
          .text("🔎 Искать везде")
          .callbackData(MUSIC_SEARCH_ALL_CALLBACK_PREFIX + musicSearchService.rememberQuery(query))
          .build()));
    }
    execute(SendMessage.builder()
        .chatId(chatId.toString())
        .text(header)
        .replyMarkup(InlineKeyboardMarkup.builder().keyboard(keyboard).build())
        .build());
  }

  private List<List<InlineKeyboardButton>> trackButtons(List<TrackResult> tracks) {
    return tracks.stream()
        .map(track -> List.of(InlineKeyboardButton.builder()
            .text(truncate(track.provider().getEmoji() + " " + track.display()))
            .callbackData(MUSIC_TRACK_CALLBACK_PREFIX + musicSearchService.rememberTrack(track))
            .build()))
        .toList();
  }

  private void handleMusicSearchEverywhere(CallbackQuery callbackQuery, UserInfo userInfo,
      String key) throws TelegramApiException {
    String query = musicSearchService.findQuery(key);
    if (query == null) {
      answerCallback(callbackQuery, "Поиск устарел — отправьте название ещё раз", true);
      return;
    }
    answerCallback(callbackQuery, "Ищу везде…");
    List<TrackResult> tracks = musicSearchService.searchEverywhere(query,
        userInfo.getMusicProvider());
    String legend = musicSearchService.availableProviders().stream()
        .map(provider -> provider.getEmoji() + " " + provider.getTitle())
        .reduce((a, b) -> a + "  " + b)
        .orElse("");
    execute(EditMessageText.builder()
        .chatId(callbackQuery.getMessage().getChatId().toString())
        .messageId(callbackQuery.getMessage().getMessageId())
        .text(tracks.isEmpty() ? "Больше ничего не нашлось :(" : "Результаты со всех сервисов:\n"
            + legend)
        .replyMarkup(tracks.isEmpty() ? null
            : InlineKeyboardMarkup.builder().keyboard(trackButtons(tracks)).build())
        .build());
  }

  private void handleMusicTrack(Long chatId, UserInfo userInfo, String key) {
    TrackResult track = musicSearchService.findTrack(key);
    if (track == null) {
      trySendText(chatId, "Результат поиска устарел — отправьте название ещё раз");
      return;
    }
    Quality quality = userInfo.getQuality();
    RequestLog requestLog = requestService.saveRequest(userInfo, RequestType.TRACK,
        track.provider().getTitle() + ": " + track.display(), quality, OutputFormat.MP3);
    processAndSend(chatId, null, requestLog, MediaType.AUDIO, OutputFormat.MP3, quality, null,
        new MusicFallback(track.display(), track.provider()),
        () -> musicSearchService.download(track, quality));
  }

  private String truncate(String text) {
    return text.length() <= BUTTON_TEXT_LIMIT ? text
        : text.substring(0, BUTTON_TEXT_LIMIT - 1) + "…";
  }

  private void sendMedia(Long chatId, Integer replyTo, File file, OutputFormat format,
      MediaType mediaType) throws TelegramApiException, IOException {
    long size = Files.size(file.toPath());
    log.info("Размер файла: {} bytes", size);
    if (size > TELEGRAM_UPLOAD_LIMIT) {
      throw new UserFacingException(mediaType == MediaType.AUDIO
          ? "К сожалению трек слишком большой :(\nМаксимальный размер - 50Мб!"
          : "Файл получился больше 50 МБ :(\nВыберите качество пониже в /settings "
              + "или вырежьте фрагмент через /crop");
    }
    InputFile inputFile = new InputFile(file);
    switch (format) {
      case MP3 -> {
        // Передаём исполнителя и название явно: так Telegram покажет их даже при кривых тегах
        MediaProcessingService.AudioTags tags = mediaProcessingService.readAudioTags(file);
        execute(SendAudio.builder()
            .chatId(chatId.toString())
            .audio(inputFile)
            .performer(tags.performer())
            .title(tags.title())
            .replyToMessageId(replyTo)
            .allowSendingWithoutReply(true)
            .build());
      }
      case MP4 -> execute(SendVideo.builder()
          .chatId(chatId.toString())
          .video(inputFile)
          .supportsStreaming(true)
          .replyToMessageId(replyTo)
          .allowSendingWithoutReply(true)
          .build());
      case WEBM, WEBP -> execute(SendDocument.builder()
          .chatId(chatId.toString())
          .document(inputFile)
          .replyToMessageId(replyTo)
          .allowSendingWithoutReply(true)
          .build());
    }
  }

  // ---------------------------------------------------------------- конвертер

  private record IncomingFile(String fileId, String fileName, Long size) {

  }

  /**
   * Файл из сообщения (документ, фото, аудио, видео, голосовое…) или {@code null}.
   */
  private IncomingFile incomingFile(Message message) {
    long id = message.getMessageId();
    if (message.hasAnimation()) {
      Animation animation = message.getAnimation();
      return new IncomingFile(animation.getFileId(), nameOrDefault(animation.getFileName(),
          animation.getMimetype(), "animation_" + id, "mp4"), animation.getFileSize());
    }
    if (message.hasDocument()) {
      Document document = message.getDocument();
      return new IncomingFile(document.getFileId(), nameOrDefault(document.getFileName(),
          document.getMimeType(), "file_" + id, ""), document.getFileSize());
    }
    if (message.hasPhoto()) {
      PhotoSize photo = message.getPhoto().stream()
          .max(Comparator.comparingInt(size -> size.getWidth() * size.getHeight()))
          .orElseThrow();
      return new IncomingFile(photo.getFileId(), "photo_" + id + ".jpg",
          photo.getFileSize() == null ? null : photo.getFileSize().longValue());
    }
    if (message.hasAudio()) {
      Audio audio = message.getAudio();
      return new IncomingFile(audio.getFileId(), nameOrDefault(audio.getFileName(),
          audio.getMimeType(), "audio_" + id, "mp3"), audio.getFileSize());
    }
    if (message.hasVideo()) {
      Video video = message.getVideo();
      return new IncomingFile(video.getFileId(), nameOrDefault(video.getFileName(),
          video.getMimeType(), "video_" + id, "mp4"), video.getFileSize());
    }
    if (message.hasVoice()) {
      Voice voice = message.getVoice();
      return new IncomingFile(voice.getFileId(), "voice_" + id + ".ogg", voice.getFileSize());
    }
    if (message.hasVideoNote()) {
      VideoNote videoNote = message.getVideoNote();
      return new IncomingFile(videoNote.getFileId(), "video_note_" + id + ".mp4",
          videoNote.getFileSize() == null ? null : videoNote.getFileSize().longValue());
    }
    return null;
  }

  /**
   * Имя файла; если его нет или у него нет расширения — достраиваем по MIME-типу.
   */
  private static String nameOrDefault(String fileName, String mimeType, String fallbackBase,
      String fallbackExtension) {
    String name = fileName == null || fileName.isBlank() ? fallbackBase : fileName;
    if (Formats.of(name).isEmpty()) {
      String extension = Formats.fromMimeType(mimeType);
      if (extension.isEmpty()) {
        extension = fallbackExtension;
      }
      if (!extension.isEmpty()) {
        name = name + "." + extension;
      }
    }
    return name;
  }

  public void executeFile(Update update) {
    Message message = update.getMessage();
    Long chatId = message.getChatId();
    try {
      UserInfo userInfo = userService.updateOrCreate(toDto(message.getFrom()));
      if (Boolean.TRUE.equals(userInfo.getBanned())
          && !botConfig.isAdmin(userInfo.getTelegramUserId())) {
        sendText(chatId, "⛔ Доступ к боту ограничен.");
        return;
      }
      IncomingFile incoming = incomingFile(message);
      if (incoming.size() != null && incoming.size() > TELEGRAM_DOWNLOAD_LIMIT) {
        sendText(chatId, "⚠️ Telegram разрешает ботам скачивать файлы только до 20 МБ. "
            + "Пришлите файл поменьше или упакуйте его в архив по частям.");
        return;
      }
      File downloaded = downloadFile(execute(GetFile.builder().fileId(incoming.fileId()).build()));
      sendScreen(chatId, conversionService.start(userInfo.getTelegramUserId(),
          downloaded.toPath(), incoming.fileName()));
    } catch (UserFacingException e) {
      trySendText(chatId, "⚠️ " + e.getMessage());
    } catch (Exception e) {
      log.error("Ошибка при приёме файла: {}", e.getMessage(), e);
      trySendText(chatId, "Не удалось обработать файл. Попробуйте ещё раз, или напишите "
          + botConfig.getAuthor());
    }
  }

  private void handleConversionCallback(CallbackQuery callbackQuery, UserInfo userInfo,
      String route) throws Exception {
    String[] parts = route.split(":");
    Long chatId = callbackQuery.getMessage().getChatId();
    Integer messageId = callbackQuery.getMessage().getMessageId();
    Optional<ConversionSession> found = conversionService.find(parts[0]);
    if (found.isEmpty()) {
      answerCallback(callbackQuery, "Файл устарел — пришлите его ещё раз", true);
      return;
    }
    ConversionSession session = found.get();
    if (session.getUserId() != userInfo.getTelegramUserId()) {
      answerCallback(callbackQuery, "Это не ваш файл", true);
      return;
    }
    if (session.getBusy().get()) {
      answerCallback(callbackQuery, "Уже конвертирую, подождите…");
      return;
    }
    String action = parts.length > 1 ? parts[1] : "";
    switch (action) {
      case "x" -> {
        answerCallback(callbackQuery, null);
        conversionService.close(session);
        editScreen(chatId, messageId, new Screen("✖️ Конвертация «"
            + Html.escape(session.getFileName()) + "» отменена", null));
      }
      case "o" -> {
        answerCallback(callbackQuery, null);
        editScreen(chatId, messageId, conversionService.overview(session));
      }
      case "f" -> {
        answerCallback(callbackQuery, null);
        editScreen(chatId, messageId, conversionService.groupScreen(session,
            Integer.parseInt(parts[2])));
      }
      case "s" -> {
        answerCallback(callbackQuery, null);
        conversionService.select(session, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        editScreen(chatId, messageId, conversionService.overview(session));
      }
      case "t" -> runConversion(callbackQuery, session, userInfo,
          () -> conversionService.convertSingle(session, Integer.parseInt(parts[2])));
      case "go" -> {
        if (session.getGroups().stream().allMatch(group -> group.getSelected() < 0)) {
          answerCallback(callbackQuery, "Выберите формат хотя бы для одного типа файлов", true);
          return;
        }
        runConversion(callbackQuery, session, userInfo,
            () -> conversionService.convertArchive(session));
      }
      default -> answerCallback(callbackQuery, null);
    }
  }

  @FunctionalInterface
  private interface Conversion {

    ConversionResult run() throws Exception;
  }

  private void runConversion(CallbackQuery callbackQuery, ConversionSession session,
      UserInfo userInfo, Conversion conversion) throws TelegramApiException {
    if (!session.getBusy().compareAndSet(false, true)) {
      answerCallback(callbackQuery, "Уже конвертирую, подождите…");
      return;
    }
    answerCallback(callbackQuery, null);
    Long chatId = callbackQuery.getMessage().getChatId();
    Integer messageId = callbackQuery.getMessage().getMessageId();
    String fileName = Html.escape(session.getFileName());
    try {
      editScreen(chatId, messageId, new Screen("⏳ Конвертирую «" + fileName + "»…", null));
      ConversionResult result = conversion.run();
      long size = Files.size(result.file());
      if (size > TELEGRAM_UPLOAD_LIMIT) {
        throw new UserFacingException("Результат получился больше 50 МБ — Telegram не даст его "
            + "отправить :(");
      }
      execute(SendDocument.builder()
          .chatId(chatId.toString())
          .document(new InputFile(result.file().toFile()))
          .caption(caption(result.description()))
          .build());
      RequestLog requestLog = requestService.saveRequest(userInfo, RequestType.CONVERT,
          result.description(), null, null);
      requestService.markDownloaded(requestLog, size);
      editScreen(chatId, messageId, new Screen("✅ Готово: " + Html.escape(
          result.description().lines().findFirst().orElse("")), null));
    } catch (Exception e) {
      String message = e instanceof UserFacingException ? e.getMessage()
          : "Не удалось сконвертировать файл";
      log.warn("Ошибка конвертации {}: {}", session.getFileName(), e.getMessage(), e);
      RequestLog requestLog = requestService.saveRequest(userInfo, RequestType.CONVERT,
          session.getFileName(), null, null);
      requestService.markFailed(requestLog, rootMessage(e));
      editScreen(chatId, messageId, new Screen("⚠️ " + Html.escape(message) + " («" + fileName
          + "»)", null));
    } finally {
      conversionService.close(session);
    }
  }

  private static String caption(String description) {
    return description.length() <= 1000 ? description : description.substring(0, 999) + "…";
  }

  private void trySendText(Long chatId, String text) {
    try {
      sendText(chatId, text);
    } catch (TelegramApiException e) {
      log.error("Ошибка при отправке сообщения: {}", e.getMessage());
    }
  }

  // ---------------------------------------------------------------- админка

  /**
   * @return {@code true}, если команда админская и обработана
   */
  private boolean handleAdminCommand(Long chatId, String command, String args)
      throws TelegramApiException {
    switch (command) {
      case "/admin" -> sendScreen(chatId, adminService.menu());
      case "/stats" -> sendScreen(chatId, adminService.stats());
      case "/requests" -> sendScreen(chatId, adminService.requests(parseNumber(args, 1)));
      case "/byusers" -> sendScreen(chatId, adminService.groupedByUser(parseNumber(args, 1)));
      case "/errors" -> sendScreen(chatId, adminService.errors(parseNumber(args, 1)));
      case "/users" -> sendScreen(chatId, adminService.users(parseNumber(args, 1)));
      case "/find" -> {
        if (args.isBlank()) {
          sendText(chatId, "Использование: /find <текст>");
        } else {
          sendScreen(chatId, adminPanel.search(args));
        }
      }
      case "/user" -> {
        if (args.isBlank()) {
          sendText(chatId, "Использование: /user <id|@username>");
        } else {
          sendScreen(chatId, adminService.user(args));
        }
      }
      case "/ban", "/unban" -> handleBan(chatId, args, "/ban".equals(command));
      case "/send" -> handleSendToUser(chatId, args);
      case "/broadcast" -> handleBroadcast(chatId, args);
      case "/export" -> handleExport(chatId);
      default -> {
        return false;
      }
    }
    return true;
  }

  /**
   * Кнопки админки перерисовывают то же сообщение.
   */
  private void handleAdminCallback(CallbackQuery callbackQuery, String route)
      throws TelegramApiException {
    answerCallback(callbackQuery, null);
    if (AdminPanel.NOOP_ROUTE.equals(route)) {
      return;
    }
    Long chatId = callbackQuery.getMessage().getChatId();
    if (AdminPanel.EXPORT_ROUTE.equals(route)) {
      handleExport(chatId);
      return;
    }
    Screen screen = adminPanel.route(route);
    if (screen != null) {
      editScreen(chatId, callbackQuery.getMessage().getMessageId(), screen);
    }
  }

  private void editScreen(Long chatId, Integer messageId, Screen screen)
      throws TelegramApiException {
    try {
      execute(EditMessageText.builder()
          .chatId(chatId.toString())
          .messageId(messageId)
          .text(screen.text())
          .parseMode(ParseMode.HTML)
          .disableWebPagePreview(true)
          .replyMarkup(screen.keyboard())
          .build());
    } catch (TelegramApiRequestException e) {
      // «message is not modified» при повторном нажатии той же кнопки — не ошибка
      if (e.getApiResponse() == null || !e.getApiResponse().contains("not modified")) {
        throw e;
      }
    }
  }

  private void sendScreen(Long chatId, Screen screen) throws TelegramApiException {
    execute(SendMessage.builder()
        .chatId(chatId.toString())
        .text(screen.text())
        .parseMode(ParseMode.HTML)
        .disableWebPagePreview(true)
        .replyMarkup(screen.keyboard())
        .build());
  }

  private void handleBan(Long chatId, String args, boolean ban) throws TelegramApiException {
    if (args.isBlank()) {
      sendText(chatId, "Использование: " + (ban ? "/ban" : "/unban") + " <id|@username>");
      return;
    }
    Optional<UserInfo> target = userService.find(args);
    if (target.isEmpty()) {
      sendText(chatId, "Пользователь «" + args + "» не найден");
      return;
    }
    if (ban && botConfig.isAdmin(target.get().getTelegramUserId())) {
      sendText(chatId, "Себя заблокировать не получится 🙂");
      return;
    }
    userService.setBanned(args, ban);
    sendScreen(chatId, adminService.user(String.valueOf(target.get().getTelegramUserId())));
  }

  private void handleSendToUser(Long chatId, String args) throws TelegramApiException {
    String[] parts = args.split("\\s+", 2);
    if (parts.length < 2 || parts[1].isBlank()) {
      sendText(chatId, "Использование: /send <id|@username> <текст>");
      return;
    }
    Optional<UserInfo> target = userService.find(parts[0]);
    if (target.isEmpty()) {
      sendText(chatId, "Пользователь «" + parts[0] + "» не найден");
      return;
    }
    try {
      sendText(target.get().getTelegramUserId(), parts[1]);
      sendText(chatId, "✅ Отправлено: " + target.get().displayName());
    } catch (TelegramApiException e) {
      sendText(chatId, "❌ Не удалось отправить: " + e.getMessage());
    }
  }

  private void handleBroadcast(Long chatId, String text) throws TelegramApiException {
    if (text.isBlank()) {
      sendText(chatId, "Использование: /broadcast <текст>");
      return;
    }
    List<Long> recipients = adminService.broadcastRecipients();
    sendText(chatId, "📣 Начинаю рассылку на " + recipients.size() + " пользователей…");
    int sent = 0;
    int failed = 0;
    for (Long recipient : recipients) {
      try {
        sendText(recipient, text);
        sent++;
      } catch (TelegramApiException e) {
        failed++;
        log.debug("Не удалось отправить рассылку {}: {}", recipient, e.getMessage());
      }
      try {
        // Не упираемся в лимит Telegram ~30 сообщений в секунду
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    sendText(chatId, "📣 Рассылка завершена. Доставлено: " + sent + ", не доставлено: " + failed
        + " (обычно это те, кто заблокировал бота)");
  }

  private void handleExport(Long chatId) throws TelegramApiException {
    execute(SendDocument.builder()
        .chatId(chatId.toString())
        .document(new InputFile(new ByteArrayInputStream(adminService.exportUsersCsv()),
            "users.csv"))
        .build());
    execute(SendDocument.builder()
        .chatId(chatId.toString())
        .document(new InputFile(new ByteArrayInputStream(adminService.exportRequestsCsv()),
            "requests.csv"))
        .build());
  }

  private int parseNumber(String args, int defaultValue) {
    try {
      return args.isBlank() ? defaultValue : Math.max(1, Integer.parseInt(args.trim()));
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  // ---------------------------------------------------------------- утилиты

  private void sendText(Long chatId, String text) throws TelegramApiException {
    for (String chunk : splitMessage(text)) {
      execute(SendMessage.builder()
          .chatId(chatId.toString())
          .text(chunk)
          .disableWebPagePreview(true)
          .build());
    }
  }

  private void sendHtml(Long chatId, String html, InlineKeyboardMarkup keyboard)
      throws TelegramApiException {
    List<String> chunks = splitMessage(html);
    for (int i = 0; i < chunks.size(); i++) {
      execute(SendMessage.builder()
          .chatId(chatId.toString())
          .text(chunks.get(i))
          .parseMode(ParseMode.HTML)
          .disableWebPagePreview(true)
          .replyMarkup(i == chunks.size() - 1 ? keyboard : null)
          .build());
    }
  }

  /**
   * Делит длинный текст по строкам, чтобы не превысить лимит Telegram на длину сообщения.
   */
  private List<String> splitMessage(String text) {
    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      if (!current.isEmpty() && current.length() + line.length() + 1 > MESSAGE_LIMIT) {
        chunks.add(current.toString());
        current.setLength(0);
      }
      while (line.length() > MESSAGE_LIMIT) {
        chunks.add(line.substring(0, MESSAGE_LIMIT));
        line = line.substring(MESSAGE_LIMIT);
      }
      if (!current.isEmpty()) {
        current.append('\n');
      }
      current.append(line);
    }
    if (!current.toString().isBlank()) {
      chunks.add(current.toString());
    }
    return chunks;
  }

  private void sendError(Long chatId) {
    try {
      sendText(chatId, "Ошибка при обработке ссылки. Попробуйте ещё раз, или напишите "
          + botConfig.getAuthor());
    } catch (TelegramApiException ex) {
      log.error("Ошибка при отправке сообщения: {}", ex.getMessage());
    }
  }

  private void answerCallback(CallbackQuery callbackQuery, String text) {
    answerCallback(callbackQuery, text, false);
  }

  private void answerCallback(CallbackQuery callbackQuery, String text, boolean alert) {
    try {
      execute(AnswerCallbackQuery.builder()
          .callbackQueryId(callbackQuery.getId())
          .text(text)
          .showAlert(alert)
          .build());
    } catch (TelegramApiException e) {
      log.debug("Не удалось ответить на callback: {}", e.getMessage());
    }
  }

  private UserInfoDto toDto(User user) {
    return UserInfoDto.builder()
        .username(user.getUserName())
        .firstName(user.getFirstName())
        .lastName(user.getLastName())
        .telegramUserId(user.getId())
        .build();
  }

  private static String rootMessage(Throwable throwable) {
    Throwable root = throwable;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
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

  @FunctionalInterface
  private interface Downloader {

    File download() throws Exception;
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
