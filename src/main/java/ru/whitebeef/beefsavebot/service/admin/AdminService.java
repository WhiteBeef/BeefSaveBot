package ru.whitebeef.beefsavebot.service.admin;

import static ru.whitebeef.beefsavebot.service.admin.AdminKeyboards.backToMenu;
import static ru.whitebeef.beefsavebot.service.admin.AdminKeyboards.button;
import static ru.whitebeef.beefsavebot.service.admin.AdminKeyboards.markup;
import static ru.whitebeef.beefsavebot.service.admin.AdminKeyboards.numbered;
import static ru.whitebeef.beefsavebot.service.admin.AdminKeyboards.pagination;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.whitebeef.beefsavebot.configuration.BotConfiguration;
import ru.whitebeef.beefsavebot.dto.Screen;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.RequestType;
import ru.whitebeef.beefsavebot.repository.RequestLogRepository;
import ru.whitebeef.beefsavebot.repository.UserInfoRepository;
import ru.whitebeef.beefsavebot.service.UserService;
import ru.whitebeef.beefsavebot.util.Html;

/**
 * Экраны админки. Каждый метод возвращает готовый HTML-текст с кнопками навигации.
 */
@Service
@RequiredArgsConstructor
public class AdminService {

  static final int REQUESTS_PAGE_SIZE = 8;
  static final int USERS_PAGE_SIZE = 10;
  /**
   * Запас до лимита Telegram в 4096 символов.
   */
  private static final int SCREEN_TEXT_LIMIT = 4000;
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");
  private static final DateTimeFormatter DATE_TIME_SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int TEXT_PREVIEW_LIMIT = 200;
  private static final int ERROR_PREVIEW_LIMIT = 200;

  private final BotConfiguration botConfiguration;
  private final UserInfoRepository userInfoRepository;
  private final RequestLogRepository requestLogRepository;
  private final UserService userService;

  public Screen menu() {
    String text = """
        🛠 <b>Админка</b>

        Разделы — кнопками ниже, навигация меняет это же сообщение.

        <b>Команды:</b>
        /stats — общая статистика
        /requests [стр.] — все запросы
        /byusers [стр.] — запросы по пользователям
        /errors [стр.] — ошибки
        /users [стр.] — пользователи
        /find &lt;текст&gt; — поиск по запросам
        /user &lt;id|@username&gt; — карточка пользователя
        /ban, /unban &lt;id|@username&gt; — блокировка
        /send &lt;id|@username&gt; &lt;текст&gt; — написать пользователю
        /broadcast &lt;текст&gt; — рассылка всем незаблокированным
        /export — выгрузка в CSV""";
    return new Screen(text, markup(List.of(
        List.of(button("📊 Статистика", "stats"), button("🕑 Запросы", "req:1")),
        List.of(button("🗂 По пользователям", "grp:1"), button("👥 Пользователи", "users:1")),
        List.of(button("❌ Ошибки", "err:1"), button("📁 Экспорт CSV", "export")))));
  }

  @Transactional(readOnly = true)
  public Screen stats() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime day = now.minusDays(1);
    LocalDateTime week = now.minusDays(7);
    LocalDateTime month = now.minusDays(30);

    StringBuilder text = new StringBuilder("📊 <b>Статистика</b>\n\n");
    text.append("👥 <b>Пользователи:</b> ").append(userInfoRepository.count()).append('\n')
        .append("   новых за 24ч / 7д / 30д: ")
        .append(userInfoRepository.countByCreatedAtAfter(day)).append(" / ")
        .append(userInfoRepository.countByCreatedAtAfter(week)).append(" / ")
        .append(userInfoRepository.countByCreatedAtAfter(month)).append('\n')
        .append("   активных за 24ч / 7д / 30д: ")
        .append(userInfoRepository.countByLastSeenAtAfter(day)).append(" / ")
        .append(userInfoRepository.countByLastSeenAtAfter(week)).append(" / ")
        .append(userInfoRepository.countByLastSeenAtAfter(month)).append('\n')
        .append("   заблокировано: ").append(userInfoRepository.countByBannedTrue()).append("\n\n");

    text.append("📥 <b>Запросы:</b> ").append(requestLogRepository.count()).append('\n')
        .append("   за 24ч / 7д / 30д: ")
        .append(requestLogRepository.countByRequestedAtAfter(day)).append(" / ")
        .append(requestLogRepository.countByRequestedAtAfter(week)).append(" / ")
        .append(requestLogRepository.countByRequestedAtAfter(month)).append('\n')
        .append("   ✅ отправлено файлов: ").append(requestLogRepository.countByDownloadedTrue())
        .append(" (за 24ч: ")
        .append(requestLogRepository.countByDownloadedTrueAndRequestedAtAfter(day)).append(")\n")
        .append("   ❌ ошибок: ").append(requestLogRepository.countByErrorMessageIsNotNull())
        .append(" (за 24ч: ")
        .append(requestLogRepository.countByErrorMessageIsNotNullAndRequestedAtAfter(day))
        .append(")\n\n");

    text.append("🗂 <b>По типам:</b>\n");
    for (Object[] row : requestLogRepository.countGroupedByType()) {
      text.append("   ").append(((RequestType) row[0]).getTitle()).append(": ").append(row[1])
          .append('\n');
    }

    text.append("\n🌐 <b>По площадкам:</b>\n");
    for (Object[] row : requestLogRepository.countGroupedByPlatform()) {
      text.append("   ").append(Html.escape(String.valueOf(row[0]))).append(": ").append(row[1])
          .append('\n');
    }

    List<Object[]> formats = requestLogRepository.countGroupedByFormat();
    if (!formats.isEmpty()) {
      text.append("\n🎞 <b>По форматам:</b>\n");
      for (Object[] row : formats) {
        text.append("   ").append(((OutputFormat) row[0]).getTitle()).append(": ").append(row[1])
            .append('\n');
      }
    }

    List<Object[]> topUsers = requestLogRepository.findTopUsers(month, PageRequest.of(0, 10));
    if (!topUsers.isEmpty()) {
      text.append("\n🏆 <b>Топ пользователей за 30 дней:</b>\n");
      int place = 1;
      for (Object[] row : topUsers) {
        text.append("   ").append(place++).append(". ").append(userLink(userFromRow(row)))
            .append(" — ").append(row[4]).append('\n');
      }
    }
    return new Screen(text.toString(), markup(List.of(
        List.of(button("🔄 Обновить", "stats"), button("🗂 По пользователям", "grp:1")),
        backToMenu())));
  }

  @Transactional(readOnly = true)
  public Screen requests(int pageNumber) {
    Page<RequestLog> page = page(pageNumber, REQUESTS_PAGE_SIZE,
        requestLogRepository::findAllByOrderByRequestedAtDesc);
    return requestsScreen("🕑 <b>Все запросы</b> (" + page.getTotalElements() + ")", page, true,
        "req:", List.of(button("🗂 Сгруппировать по пользователям", "grp:1")));
  }

  @Transactional(readOnly = true)
  public Screen errors(int pageNumber) {
    Page<RequestLog> page = page(pageNumber, REQUESTS_PAGE_SIZE,
        requestLogRepository::findByErrorMessageIsNotNullOrderByRequestedAtDesc);
    return requestsScreen("❌ <b>Ошибки</b> (" + page.getTotalElements() + ")", page, true, "err:",
        List.of());
  }

  /**
   * Поиск по тексту запросов. Сам запрос слишком длинный для callback_data, поэтому маршрут
   * пагинации передаётся снаружи.
   */
  @Transactional(readOnly = true)
  public Screen search(String query, int pageNumber, String routePrefix) {
    Page<RequestLog> page = page(pageNumber, REQUESTS_PAGE_SIZE, pageable ->
        requestLogRepository.findByUrlContainingIgnoreCaseOrderByRequestedAtDesc(query, pageable));
    return requestsScreen("🔎 <b>Запросы с «" + Html.escape(query) + "»</b> ("
        + page.getTotalElements() + ")", page, true, routePrefix, List.of());
  }

  @Transactional(readOnly = true)
  public Screen groupedByUser(int pageNumber) {
    Page<Object[]> page = page(pageNumber, USERS_PAGE_SIZE,
        requestLogRepository::findGroupedByUser);
    int first = page.getNumber() * USERS_PAGE_SIZE + 1;
    List<String> items = new ArrayList<>();
    List<String> routes = new ArrayList<>();
    int number = first;
    for (Object[] row : page.getContent()) {
      UserInfo user = userFromRow(row);
      items.add(number++ + ". " + userLink(user) + "\n"
          + "   запросов: <b>" + row[4] + "</b> · ✅ " + row[5] + " · ❌ " + row[6]
          + " · последний: " + formatDate((LocalDateTime) row[7]));
      routes.add("ur:" + user.getTelegramUserId() + ":1");
    }
    String header = "🗂 <b>Запросы по пользователям</b> (пользователей: "
        + page.getTotalElements() + ")\n<i>Номер — запросы пользователя</i>";
    List<List<InlineKeyboardButton>> rows = new ArrayList<>(numbered(routes, first));
    rows.add(pagination(page, "grp:"));
    rows.add(List.of(button("🕑 Все запросы подряд", "req:1")));
    rows.add(backToMenu());
    return new Screen(fit(header, items, "Запросов пока нет"), markup(rows));
  }

  @Transactional(readOnly = true)
  public Screen users(int pageNumber) {
    Page<UserInfo> page = page(pageNumber, USERS_PAGE_SIZE, userInfoRepository::findAllByActivity);
    int first = page.getNumber() * USERS_PAGE_SIZE + 1;
    List<String> items = new ArrayList<>();
    List<String> routes = new ArrayList<>();
    int number = first;
    for (UserInfo user : page.getContent()) {
      items.add(number++ + ". " + userLink(user)
          + (Boolean.TRUE.equals(user.getBanned()) ? " 🚫" : "") + "\n"
          + "   запросов: " + requestLogRepository.countByUserInfo(user)
          + " · был: " + formatDate(user.getLastSeenAt())
          + " · " + user.getOutputFormat().getTitle() + "/" + user.getQuality().getTitle());
      routes.add("u:" + user.getTelegramUserId());
    }
    String header = "👥 <b>Пользователи</b> (" + page.getTotalElements() + ")\n"
        + "<i>По последней активности. Номер — карточка пользователя</i>";
    List<List<InlineKeyboardButton>> rows = new ArrayList<>(numbered(routes, first));
    rows.add(pagination(page, "users:"));
    rows.add(backToMenu());
    return new Screen(fit(header, items, "Пользователей пока нет"), markup(rows));
  }

  @Transactional(readOnly = true)
  public Screen user(String idOrUsername) {
    Optional<UserInfo> found = userService.find(idOrUsername);
    if (found.isEmpty()) {
      return new Screen("Пользователь «" + Html.escape(idOrUsername) + "» не найден",
          markup(List.of(List.of(button("👥 Пользователи", "users:1")), backToMenu())));
    }
    UserInfo user = found.get();
    long id = user.getTelegramUserId();
    boolean banned = Boolean.TRUE.equals(user.getBanned());
    String text = "👤 " + userLink(user) + "\n\n"
        + "ID: <code>" + id + "</code>\n"
        + "Username: " + (user.getUsername() == null ? "—" : "@" + Html.escape(user.getUsername()))
        + "\n"
        + "Первый запуск: " + formatDate(user.getCreatedAt()) + "\n"
        + "Последняя активность: " + formatDate(user.getLastSeenAt()) + "\n"
        + "Настройки: " + user.getOutputFormat().getTitle() + " / " + user.getQuality().getTitle()
        + "\n"
        + "Статус: " + (banned ? "🚫 заблокирован" : "активен") + "\n"
        + "Всего запросов: " + requestLogRepository.countByUserInfo(user) + "\n\n"
        + "Написать: <code>/send " + id + " текст</code>";
    List<InlineKeyboardButton> actions = new ArrayList<>();
    actions.add(button("📜 Запросы", "ur:" + id + ":1"));
    if (!botConfiguration.isAdmin(id)) {
      actions.add(banned ? button("✅ Разблокировать", "unban:" + id)
          : button("🚫 Заблокировать", "ban:" + id));
    }
    return new Screen(text, markup(List.of(actions,
        List.of(button("👥 Пользователи", "users:1"), button("🗂 По пользователям", "grp:1")),
        backToMenu())));
  }

  @Transactional(readOnly = true)
  public Screen userRequests(long telegramId, int pageNumber) {
    Optional<UserInfo> found = userInfoRepository.findByTelegramUserId(telegramId);
    if (found.isEmpty()) {
      return user(String.valueOf(telegramId));
    }
    Page<RequestLog> page = page(pageNumber, REQUESTS_PAGE_SIZE, pageable ->
        requestLogRepository.findByUserInfoOrderByRequestedAtDesc(found.get(), pageable));
    return requestsScreen("📜 <b>Запросы</b> " + userLink(found.get()) + " ("
            + page.getTotalElements() + ")", page, false, "ur:" + telegramId + ":",
        List.of(button("👤 Карточка", "u:" + telegramId),
            button("🗂 По пользователям", "grp:1")));
  }

  @Transactional(readOnly = true)
  public List<Long> broadcastRecipients() {
    return userInfoRepository.findNotBannedTelegramIds();
  }

  @Transactional(readOnly = true)
  public byte[] exportRequestsCsv() {
    StringBuilder csv = new StringBuilder("﻿");
    csv.append("id;time;telegram_id;username;name;type;text;format;quality;downloaded;file_size;error\n");
    for (RequestLog request : requestLogRepository.findAllByOrderByIdAsc()) {
      UserInfo user = request.getUserInfo();
      csv.append(request.getId()).append(';')
          .append(formatDateFull(request.getRequestedAt())).append(';')
          .append(user == null ? "" : user.getTelegramUserId()).append(';')
          .append(csvValue(user == null ? null : user.getUsername())).append(';')
          .append(csvValue(user == null ? null : fullName(user))).append(';')
          .append(request.getRequestType()).append(';')
          .append(csvValue(request.getUrl())).append(';')
          .append(request.getOutputFormat() == null ? "" : request.getOutputFormat()).append(';')
          .append(request.getQuality() == null ? "" : request.getQuality()).append(';')
          .append(Boolean.TRUE.equals(request.getDownloaded())).append(';')
          .append(request.getFileSize() == null ? "" : request.getFileSize()).append(';')
          .append(csvValue(request.getErrorMessage())).append('\n');
    }
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Transactional(readOnly = true)
  public byte[] exportUsersCsv() {
    StringBuilder csv = new StringBuilder("﻿");
    csv.append("telegram_id;username;first_name;last_name;created_at;last_seen_at;format;quality;banned;requests\n");
    for (UserInfo user : userInfoRepository.findAll(Sort.by("id"))) {
      csv.append(user.getTelegramUserId()).append(';')
          .append(csvValue(user.getUsername())).append(';')
          .append(csvValue(user.getFirstName())).append(';')
          .append(csvValue(user.getLastName())).append(';')
          .append(formatDateFull(user.getCreatedAt())).append(';')
          .append(formatDateFull(user.getLastSeenAt())).append(';')
          .append(user.getOutputFormat()).append(';')
          .append(user.getQuality()).append(';')
          .append(Boolean.TRUE.equals(user.getBanned())).append(';')
          .append(requestLogRepository.countByUserInfo(user)).append('\n');
    }
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Загружает страницу (нумерация с 1); если такой уже нет — например, данные удалились, —
   * последнюю существующую.
   */
  private static <T> Page<T> page(int pageNumber, int size, Function<Pageable, Page<T>> query) {
    Page<T> page = query.apply(PageRequest.of(Math.max(0, pageNumber - 1), size));
    if (page.getContent().isEmpty() && page.getTotalPages() > 0
        && page.getNumber() >= page.getTotalPages()) {
      page = query.apply(PageRequest.of(page.getTotalPages() - 1, size));
    }
    return page;
  }

  private Screen requestsScreen(String header, Page<RequestLog> page, boolean withUser,
      String routePrefix, List<InlineKeyboardButton> extraButtons) {
    Function<RequestLog, String> formatter = request -> formatRequest(request, withUser);
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    rows.add(pagination(page, routePrefix));
    for (InlineKeyboardButton extra : extraButtons) {
      rows.add(List.of(extra));
    }
    rows.add(backToMenu());
    return new Screen(fit(header, page.getContent().stream().map(formatter).toList(),
        "Ничего не найдено"), markup(rows));
  }

  /**
   * Склеивает элементы страницы, отбрасывая хвост, если текст не влезает в одно сообщение.
   */
  private String fit(String header, List<String> items, String emptyText) {
    if (items.isEmpty()) {
      return header + "\n\n" + emptyText;
    }
    StringBuilder text = new StringBuilder(header);
    for (String item : items) {
      if (text.length() + item.length() + 2 > SCREEN_TEXT_LIMIT) {
        text.append("\n\n…");
        break;
      }
      text.append("\n\n").append(item);
    }
    return text.toString();
  }

  private String formatRequest(RequestLog request, boolean withUser) {
    StringBuilder line = new StringBuilder()
        .append("<b>").append(formatDate(request.getRequestedAt())).append("</b>");
    if (withUser && request.getUserInfo() != null) {
      line.append(" · ").append(userLink(request.getUserInfo()));
    }
    line.append(" · ").append(request.getRequestType().getTitle());
    if (request.getOutputFormat() != null) {
      line.append(" · ").append(request.getOutputFormat().getTitle());
      if (request.getQuality() != null) {
        line.append('/').append(request.getQuality().getTitle());
      }
    }
    if (Boolean.TRUE.equals(request.getDownloaded())) {
      line.append(" · ✅");
      if (request.getFileSize() != null) {
        line.append(String.format(" %.1f МБ", request.getFileSize() / 1024.0 / 1024.0));
      }
    } else if (request.getErrorMessage() != null) {
      line.append(" · ❌");
    }
    line.append('\n').append("<code>").append(Html.escape(truncate(request.getUrl(),
        TEXT_PREVIEW_LIMIT))).append("</code>");
    if (request.getErrorMessage() != null) {
      line.append('\n').append("<i>").append(Html.escape(truncate(
          request.getErrorMessage().replace('\n', ' '), ERROR_PREVIEW_LIMIT))).append("</i>");
    }
    return line.toString();
  }

  private static UserInfo userFromRow(Object[] row) {
    return UserInfo.builder()
        .telegramUserId((Long) row[0])
        .username((String) row[1])
        .firstName((String) row[2])
        .lastName((String) row[3])
        .build();
  }

  private String userLink(UserInfo user) {
    return "<a href=\"tg://user?id=" + user.getTelegramUserId() + "\">"
        + Html.escape(user.displayName()) + "</a> [<code>" + user.getTelegramUserId() + "</code>]";
  }

  private String fullName(UserInfo user) {
    return ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
        + (user.getLastName() == null ? "" : user.getLastName())).trim();
  }

  private String formatDate(LocalDateTime dateTime) {
    return dateTime == null ? "—" : toDisplayZone(dateTime).format(DATE_TIME);
  }

  private String formatDateFull(LocalDateTime dateTime) {
    return dateTime == null ? "" : toDisplayZone(dateTime).format(DATE_TIME_SECONDS);
  }

  /**
   * Даты хранятся во времени сервера, показываем их в часовом поясе из конфига.
   */
  private LocalDateTime toDisplayZone(LocalDateTime dateTime) {
    return dateTime.atZone(ZoneId.systemDefault())
        .withZoneSameInstant(botConfiguration.getZoneId())
        .toLocalDateTime();
  }

  private static String truncate(String text, int limit) {
    if (text == null) {
      return "";
    }
    return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
  }

  private static String csvValue(String value) {
    if (value == null) {
      return "";
    }
    return '"' + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + '"';
  }
}
