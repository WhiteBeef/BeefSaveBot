package ru.whitebeef.beefsavebot.service.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.configuration.BotConfiguration;
import ru.whitebeef.beefsavebot.dto.Screen;
import ru.whitebeef.beefsavebot.service.UserService;

/**
 * Разбирает маршруты кнопок админки ({@code adm:<маршрут>}) и строит нужный экран.
 * <ul>
 *   <li>{@code menu}, {@code stats}</li>
 *   <li>{@code req:<стр>} — все запросы, {@code err:<стр>} — ошибки</li>
 *   <li>{@code grp:<стр>} — запросы, сгруппированные по пользователям</li>
 *   <li>{@code users:<стр>}, {@code u:<id>} — пользователи и карточка</li>
 *   <li>{@code ur:<id>:<стр>} — запросы пользователя</li>
 *   <li>{@code ban:<id>}, {@code unban:<id>}</li>
 *   <li>{@code f:<ключ>:<стр>} — результаты поиска</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AdminPanel {

  public static final String CALLBACK_PREFIX = AdminKeyboards.PREFIX;
  public static final String EXPORT_ROUTE = "export";
  public static final String NOOP_ROUTE = AdminKeyboards.NOOP;
  private static final int MAX_STORED_SEARCHES = 200;

  private final AdminService adminService;
  private final UserService userService;
  private final BotConfiguration botConfiguration;

  /**
   * Текст поиска не помещается в callback_data (64 байта), поэтому храним его в памяти по
   * короткому ключу. После перезапуска старые кнопки поиска просто вернут в меню.
   */
  private final Map<String, String> searches = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
      return size() > MAX_STORED_SEARCHES;
    }
  };
  private final AtomicLong searchCounter = new AtomicLong();

  /**
   * @return экран для маршрута или {@code null}, если маршрут не ведёт на экран
   */
  public Screen route(String route) {
    String[] parts = route.split(":");
    try {
      return switch (parts[0]) {
        case "menu" -> adminService.menu();
        case "stats" -> adminService.stats();
        case "req" -> adminService.requests(page(parts, 1));
        case "err" -> adminService.errors(page(parts, 1));
        case "grp" -> adminService.groupedByUser(page(parts, 1));
        case "users" -> adminService.users(page(parts, 1));
        case "u" -> adminService.user(parts[1]);
        case "ur" -> adminService.userRequests(Long.parseLong(parts[1]), page(parts, 2));
        case "ban", "unban" -> setBanned(Long.parseLong(parts[1]), "ban".equals(parts[0]));
        case "f" -> {
          String query;
          synchronized (searches) {
            query = searches.get(parts[1]);
          }
          yield query == null ? adminService.menu()
              : adminService.search(query, page(parts, 2), "f:" + parts[1] + ":");
        }
        default -> null;
      };
    } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
      return adminService.menu();
    }
  }

  public Screen search(String query) {
    String key = Long.toString(searchCounter.incrementAndGet(), 36);
    synchronized (searches) {
      searches.put(key, query);
    }
    return adminService.search(query, 1, "f:" + key + ":");
  }

  private Screen setBanned(long telegramId, boolean banned) {
    if (!botConfiguration.isAdmin(telegramId)) {
      userService.setBanned(String.valueOf(telegramId), banned);
    }
    return adminService.user(String.valueOf(telegramId));
  }

  private static int page(String[] parts, int index) {
    return parts.length > index ? Math.max(1, Integer.parseInt(parts[index])) : 1;
  }
}
