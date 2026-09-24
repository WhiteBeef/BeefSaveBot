package ru.whitebeef.beefsavebot.service.admin;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/**
 * Кнопки админки. callback_data имеет вид {@code adm:<маршрут>}, маршруты разбирает
 * {@link AdminPanel}.
 */
final class AdminKeyboards {

  static final String PREFIX = "adm:";
  static final String NOOP = "noop";

  private AdminKeyboards() {
  }

  static InlineKeyboardButton button(String text, String route) {
    return InlineKeyboardButton.builder().text(text).callbackData(PREFIX + route).build();
  }

  /**
   * Строка пагинации: « ‹ 3/10 › ». {@code routePrefix} дополняется номером страницы (с 1).
   */
  static List<InlineKeyboardButton> pagination(Page<?> page, String routePrefix) {
    List<InlineKeyboardButton> row = new ArrayList<>();
    int current = page.getNumber() + 1;
    int total = Math.max(1, page.getTotalPages());
    if (total <= 1) {
      return row;
    }
    if (current > 2) {
      row.add(button("«", routePrefix + 1));
    }
    if (current > 1) {
      row.add(button("‹", routePrefix + (current - 1)));
    }
    row.add(button(current + " / " + total, NOOP));
    if (current < total) {
      row.add(button("›", routePrefix + (current + 1)));
    }
    if (current < total - 1) {
      row.add(button("»", routePrefix + total));
    }
    return row;
  }

  static List<InlineKeyboardButton> backToMenu() {
    return List.of(button("⬅️ Меню", "menu"));
  }

  static InlineKeyboardMarkup markup(List<List<InlineKeyboardButton>> rows) {
    return InlineKeyboardMarkup.builder()
        .keyboard(rows.stream().filter(row -> !row.isEmpty()).toList())
        .build();
  }

  /**
   * Нумерованные кнопки для элементов страницы, по 5 в ряд.
   */
  static List<List<InlineKeyboardButton>> numbered(List<String> routes, int firstNumber) {
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    List<InlineKeyboardButton> row = new ArrayList<>();
    for (int i = 0; i < routes.size(); i++) {
      row.add(button(String.valueOf(firstNumber + i), routes.get(i)));
      if (row.size() == 5) {
        rows.add(row);
        row = new ArrayList<>();
      }
    }
    if (!row.isEmpty()) {
      rows.add(row);
    }
    return rows;
  }
}
