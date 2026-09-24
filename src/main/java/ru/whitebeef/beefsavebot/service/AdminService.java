package ru.whitebeef.beefsavebot.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.whitebeef.beefsavebot.configuration.BotConfiguration;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.RequestType;
import ru.whitebeef.beefsavebot.repository.RequestLogRepository;
import ru.whitebeef.beefsavebot.repository.UserInfoRepository;
import ru.whitebeef.beefsavebot.util.Html;

/**
 * Данные для админки. Все методы возвращают готовый HTML-текст для Telegram.
 */
@Service
@RequiredArgsConstructor
public class AdminService {

  public static final int USERS_PAGE_SIZE = 20;
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");
  private static final DateTimeFormatter DATE_TIME_SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int TEXT_PREVIEW_LIMIT = 300;
  private static final int ERROR_PREVIEW_LIMIT = 300;

  private final BotConfiguration botConfiguration;
  private final UserInfoRepository userInfoRepository;
  private final RequestLogRepository requestLogRepository;
  private final UserService userService;

  public String helpText() {
    return """
        🛠 <b>Админка</b>

        /stats — общая статистика
        /requests [N] — последние N запросов (по умолчанию 20)
        /find &lt;текст&gt; — поиск по запросам
        /errors [N] — последние ошибки
        /users [страница] — пользователи по последней активности
        /user &lt;id|@username&gt; — карточка пользователя и его запросы
        /ban &lt;id|@username&gt; — заблокировать
        /unban &lt;id|@username&gt; — разблокировать
        /send &lt;id|@username&gt; &lt;текст&gt; — написать пользователю от имени бота
        /broadcast &lt;текст&gt; — рассылка всем незаблокированным
        /export — выгрузка пользователей и запросов в CSV""";
  }

  @Transactional(readOnly = true)
  public String statsText() {
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
        UserInfo user = UserInfo.builder()
            .telegramUserId((Long) row[0])
            .username((String) row[1])
            .firstName((String) row[2])
            .lastName((String) row[3])
            .build();
        text.append("   ").append(place++).append(". ").append(userLink(user))
            .append(" — ").append(row[4]).append('\n');
      }
    }
    return text.toString();
  }

  @Transactional(readOnly = true)
  public String recentRequestsText(int limit) {
    Page<RequestLog> page = requestLogRepository.findAllByOrderByRequestedAtDesc(
        PageRequest.of(0, limit));
    return formatRequests("🕑 <b>Последние запросы</b>", page.getContent(), true);
  }

  @Transactional(readOnly = true)
  public String searchRequestsText(String query, int limit) {
    Page<RequestLog> page = requestLogRepository.findByUrlContainingIgnoreCaseOrderByRequestedAtDesc(
        query, PageRequest.of(0, limit));
    return formatRequests("🔎 <b>Запросы с «" + Html.escape(query) + "»</b> (найдено "
        + page.getTotalElements() + ")", page.getContent(), true);
  }

  @Transactional(readOnly = true)
  public String errorsText(int limit) {
    Page<RequestLog> page = requestLogRepository.findByErrorMessageIsNotNullOrderByRequestedAtDesc(
        PageRequest.of(0, limit));
    return formatRequests("❌ <b>Последние ошибки</b> (всего " + page.getTotalElements() + ")",
        page.getContent(), true);
  }

  @Transactional(readOnly = true)
  public String usersText(int pageNumber) {
    Page<UserInfo> page = userInfoRepository.findAllByActivity(
        PageRequest.of(Math.max(0, pageNumber - 1), USERS_PAGE_SIZE));
    StringBuilder text = new StringBuilder("👥 <b>Пользователи</b> (всего ")
        .append(page.getTotalElements()).append(")\n\n");
    int index = page.getNumber() * USERS_PAGE_SIZE + 1;
    for (UserInfo user : page.getContent()) {
      text.append(index++).append(". ").append(userLink(user));
      if (Boolean.TRUE.equals(user.getBanned())) {
        text.append(" 🚫");
      }
      text.append('\n')
          .append("   запросов: ").append(requestLogRepository.countByUserInfo(user))
          .append(" · был: ").append(formatDate(user.getLastSeenAt()))
          .append(" · ").append(user.getOutputFormat().getTitle()).append('/')
          .append(user.getQuality().getTitle()).append('\n');
    }
    if (page.getTotalPages() > 1) {
      text.append("\nСтраница ").append(page.getNumber() + 1).append(" из ")
          .append(page.getTotalPages());
      if (page.hasNext()) {
        text.append(". Дальше: /users ").append(page.getNumber() + 2);
      }
    }
    return text.toString();
  }

  @Transactional(readOnly = true)
  public String userText(String idOrUsername, int requestsLimit) {
    Optional<UserInfo> found = userService.find(idOrUsername);
    if (found.isEmpty()) {
      return "Пользователь «" + Html.escape(idOrUsername) + "» не найден";
    }
    UserInfo user = found.get();
    String header = "👤 " + userLink(user) + "\n"
        + "ID: <code>" + user.getTelegramUserId() + "</code>\n"
        + "Username: " + (user.getUsername() == null ? "—" : "@" + Html.escape(user.getUsername()))
        + "\n"
        + "Первый запуск: " + formatDate(user.getCreatedAt()) + "\n"
        + "Последняя активность: " + formatDate(user.getLastSeenAt()) + "\n"
        + "Настройки: " + user.getOutputFormat().getTitle() + " / " + user.getQuality().getTitle()
        + "\n"
        + "Статус: " + (Boolean.TRUE.equals(user.getBanned()) ? "🚫 заблокирован" : "активен")
        + "\n"
        + "Всего запросов: " + requestLogRepository.countByUserInfo(user);
    Page<RequestLog> requests = requestLogRepository.findByUserInfoOrderByRequestedAtDesc(user,
        PageRequest.of(0, requestsLimit));
    return formatRequests(header + "\n\n<b>Последние запросы:</b>", requests.getContent(), false);
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

  private String formatRequests(String title, List<RequestLog> requests, boolean withUser) {
    if (requests.isEmpty()) {
      return title + "\n\nНичего не найдено";
    }
    return title + "\n\n" + requests.stream()
        .map(request -> formatRequest(request, withUser))
        .collect(Collectors.joining("\n\n"));
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
