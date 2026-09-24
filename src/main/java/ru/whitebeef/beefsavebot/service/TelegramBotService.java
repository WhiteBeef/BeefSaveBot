package ru.whitebeef.beefsavebot.service;


import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
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
import ru.whitebeef.beefsavebot.dto.UserInfoDto;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.model.RequestType;
import ru.whitebeef.beefsavebot.service.download.MediaType;
import ru.whitebeef.beefsavebot.service.download.VideoDownloadService;
import ru.whitebeef.beefsavebot.service.download.YandexMusicDownloadService;
import ru.whitebeef.beefsavebot.service.download.YandexMusicDownloadService.TrackSearchResult;
import ru.whitebeef.beefsavebot.service.media.CropRange;
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
  private static final String ADMIN_CALLBACK_PREFIX = "adm:";
  private static final int BUTTON_TEXT_LIMIT = 64;
  private static final int MESSAGE_LIMIT = 4000;
  /**
   * Ограничение Bot API на отправку файлов.
   */
  private static final long TELEGRAM_UPLOAD_LIMIT = 50L * 1024 * 1024;

  private final BotConfiguration botConfig;
  private final DownloadConfiguration downloadConfiguration;
  private final VideoDownloadService videoDownloadService;
  private final YandexMusicDownloadService yandexMusicDownloadService;
  private final MediaProcessingService mediaProcessingService;
  private final RequestService requestService;
  private final UserService userService;
  private final AdminService adminService;
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
            new BotCommand("requests", "Последние запросы"),
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
        handleCommand(chatId, userInfo, text, admin);
        return;
      }
      handleDownload(chatId, userInfo, text, null);
    } catch (Exception e) {
      log.error("Ошибка при обработке сообщения '{}': {}", text, e.getMessage(), e);
      sendError(chatId);
    }
  }

  public void executeCallback(Update update) {
    CallbackQuery callbackQuery = update.getCallbackQuery();
    String data = callbackQuery.getData();
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
      } else if (data.startsWith(QUALITY_CALLBACK_PREFIX) || data.startsWith(FORMAT_CALLBACK_PREFIX)) {
        handleSettingsCallback(callbackQuery, userInfo, data);
      } else if (data.startsWith(ADMIN_CALLBACK_PREFIX) && admin) {
        answerCallback(callbackQuery, null);
        handleAdminCommand(chatId, "/" + data.substring(ADMIN_CALLBACK_PREFIX.length()), "");
      } else {
        answerCallback(callbackQuery, null);
      }
    } catch (Exception e) {
      log.error("Ошибка при обработке callback '{}': {}", data, e.getMessage(), e);
      sendError(chatId);
    }
  }

  // ---------------------------------------------------------------- команды

  private void handleCommand(Long chatId, UserInfo userInfo, String text, boolean admin)
      throws TelegramApiException {
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
        + "🎵 Искать треки в Яндекс Музыке — просто напиши название песни\n"
        + "⚙️ Присылать файл в нужном формате (MP4, MP3, WEBM, WEBP) и качестве — /settings\n"
        + "✂️ Вырезать фрагмент видео с точностью до кадра — /crop\n\n"
        + "<b>Команды:</b>\n"
        + "/settings — качество и формат\n"
        + "/crop &lt;ссылка&gt; &lt;начало&gt; &lt;конец&gt; — обрезать видео\n"
        + "/help — это сообщение\n\n"
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
        + "<i>Треки из Яндекс Музыки всегда приходят в MP3, качество влияет на битрейт.</i>";
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
    return InlineKeyboardMarkup.builder().keyboard(List.of(qualityRow, formatRow)).build();
  }

  private void handleSettingsCallback(CallbackQuery callbackQuery, UserInfo userInfo, String data)
      throws TelegramApiException {
    Quality quality = data.startsWith(QUALITY_CALLBACK_PREFIX)
        ? Quality.parse(data.substring(QUALITY_CALLBACK_PREFIX.length())) : null;
    OutputFormat format = data.startsWith(FORMAT_CALLBACK_PREFIX)
        ? OutputFormat.parse(data.substring(FORMAT_CALLBACK_PREFIX.length())) : null;
    if ((quality == null && format == null)
        || quality == userInfo.getQuality() || format == userInfo.getOutputFormat()) {
      answerCallback(callbackQuery, null);
      return;
    }
    UserInfo updated = userService.updateSettings(userInfo.getTelegramUserId(), quality, format);
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
    RequestType requestType = crop == null ? RequestType.DOWNLOAD : RequestType.CROP;
    String requestText = crop == null ? url : url + " " + crop.start().source() + " "
        + crop.end().source();

    if (!videoDownloadService.canDownloadVideo(url)) {
      if (crop == null) {
        requestService.saveRequest(userInfo, RequestType.SEARCH, url, null, null);
        offerSearchResults(chatId, url);
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
    processAndSend(chatId, requestLog, mediaType, format, quality, crop,
        () -> videoDownloadService.downloadVideo(url, options));
  }

  private void handleTrackCallback(Long chatId, UserInfo userInfo, String trackId) {
    Quality quality = userInfo.getQuality();
    RequestLog requestLog = requestService.saveRequest(userInfo, RequestType.TRACK,
        "yandex-music-search:" + trackId, quality, OutputFormat.MP3);
    processAndSend(chatId, requestLog, MediaType.AUDIO, OutputFormat.MP3, quality, null,
        () -> yandexMusicDownloadService.downloadTrackById(trackId, quality));
  }

  private void processAndSend(Long chatId, RequestLog requestLog, MediaType mediaType,
      OutputFormat format, Quality quality, CropRange crop, Downloader downloader) {
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
      sendMedia(chatId, result, format, mediaType);
      requestService.markDownloaded(requestLog, size);
    } catch (UserFacingException e) {
      log.warn("Запрос {} не выполнен: {}", requestLog.getUrl(), e.getMessage());
      requestService.markFailed(requestLog, e.getMessage());
      try {
        sendText(chatId, "⚠️ " + e.getMessage());
      } catch (TelegramApiException ex) {
        log.error("Ошибка при отправке сообщения: {}", ex.getMessage());
      }
    } catch (Exception e) {
      log.error("Ошибка при обработке {}: {}", requestLog.getUrl(), e.getMessage(), e);
      requestService.markFailed(requestLog, rootMessage(e));
      sendError(chatId);
    } finally {
      cleanup(result);
      if (source != result) {
        cleanup(source);
      }
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
                  + "\n\nСвяжитесь с " + botConfig.getAuthor()
                  + ", если вам необходим какой-то сайт, которого нет в списке :0")
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

  private void sendMedia(Long chatId, File file, OutputFormat format, MediaType mediaType)
      throws TelegramApiException, IOException {
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
      case MP3 -> execute(SendAudio.builder()
          .chatId(chatId.toString())
          .audio(inputFile)
          .build());
      case MP4 -> execute(SendVideo.builder()
          .chatId(chatId.toString())
          .video(inputFile)
          .supportsStreaming(true)
          .build());
      case WEBM, WEBP -> execute(SendDocument.builder()
          .chatId(chatId.toString())
          .document(inputFile)
          .build());
    }
  }

  // ---------------------------------------------------------------- админка

  /**
   * @return {@code true}, если команда админская и обработана
   */
  private boolean handleAdminCommand(Long chatId, String command, String args)
      throws TelegramApiException {
    switch (command) {
      case "/admin" -> sendHtml(chatId, adminService.helpText(), adminKeyboard());
      case "/stats" -> sendHtml(chatId, adminService.statsText(), adminKeyboard());
      case "/requests" -> sendHtml(chatId,
          adminService.recentRequestsText(parseNumber(args, 20, 100)), null);
      case "/find" -> {
        if (args.isBlank()) {
          sendText(chatId, "Использование: /find <текст>");
        } else {
          sendHtml(chatId, adminService.searchRequestsText(args, 30), null);
        }
      }
      case "/errors" -> sendHtml(chatId, adminService.errorsText(parseNumber(args, 10, 50)), null);
      case "/users" -> sendHtml(chatId, adminService.usersText(parseNumber(args, 1, 100_000)),
          null);
      case "/user" -> {
        if (args.isBlank()) {
          sendText(chatId, "Использование: /user <id|@username>");
        } else {
          sendHtml(chatId, adminService.userText(args, 15), null);
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

  private InlineKeyboardMarkup adminKeyboard() {
    return InlineKeyboardMarkup.builder()
        .keyboard(List.of(
            List.of(adminButton("📊 Статистика", "stats"), adminButton("🕑 Запросы", "requests")),
            List.of(adminButton("👥 Пользователи", "users"), adminButton("❌ Ошибки", "errors")),
            List.of(adminButton("📁 Экспорт CSV", "export"))))
        .build();
  }

  private InlineKeyboardButton adminButton(String text, String command) {
    return InlineKeyboardButton.builder()
        .text(text)
        .callbackData(ADMIN_CALLBACK_PREFIX + command)
        .build();
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
    sendText(chatId, (ban ? "🚫 Заблокирован: " : "✅ Разблокирован: ")
        + target.get().displayName());
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

  private int parseNumber(String args, int defaultValue, int max) {
    try {
      return args.isBlank() ? defaultValue
          : Math.max(1, Math.min(max, Integer.parseInt(args.trim())));
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
    try {
      execute(AnswerCallbackQuery.builder()
          .callbackQueryId(callbackQuery.getId())
          .text(text)
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
