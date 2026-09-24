package ru.whitebeef.beefsavebot.service.media;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Таймкод для обрезки. Поддерживаемые записи:
 * <ul>
 *   <li>{@code 83}, {@code 83.5} — секунды;</li>
 *   <li>{@code 1:23}, {@code 1:23.480} — минуты:секунды;</li>
 *   <li>{@code 0:01:23}, {@code 0:01:23.480} — часы:минуты:секунды;</li>
 *   <li>{@code 0:01:23:12} или {@code 0:01:23;12} — часы:минуты:секунды:кадр (точность до кадра).</li>
 * </ul>
 *
 * @param seconds целые и дробные секунды
 * @param frame   номер кадра внутри секунды или {@code null}
 */
public record TimeCode(double seconds, Integer frame, String source) {

  private static final Pattern PATTERN = Pattern.compile(
      "^(?:(?:(\\d+):)?(\\d{1,2}):)?(\\d+(?:[.,]\\d+)?)(?:[:;](\\d{1,3}))?$");

  public static TimeCode parse(String value) {
    String text = value == null ? "" : value.trim();
    Matcher matcher = PATTERN.matcher(text);
    if (!matcher.matches()) {
      throw new UserFacingException("Не понял таймкод «" + text + "»");
    }
    String hours = matcher.group(1);
    String minutes = matcher.group(2);
    String secondsText = matcher.group(3).replace(',', '.');
    String frameText = matcher.group(4);

    if (frameText != null && (hours == null || secondsText.contains("."))) {
      // Кадр указывается только в полном формате ЧЧ:ММ:СС:КК, иначе 1:23:12 была бы неоднозначна
      throw new UserFacingException("Кадр указывается в формате ЧЧ:ММ:СС:КК, например 0:01:23:12");
    }
    double seconds = Double.parseDouble(secondsText);
    if ((minutes != null || hours != null) && seconds >= 60) {
      throw new UserFacingException("Секунд в таймкоде «" + text + "» должно быть меньше 60");
    }
    if (minutes != null && hours != null && Integer.parseInt(minutes) >= 60) {
      throw new UserFacingException("Минут в таймкоде «" + text + "» должно быть меньше 60");
    }
    double total = seconds
        + (minutes == null ? 0 : Integer.parseInt(minutes) * 60.0)
        + (hours == null ? 0 : Integer.parseInt(hours) * 3600.0);
    return new TimeCode(total, frameText == null ? null : Integer.parseInt(frameText), text);
  }

  /**
   * Переводит таймкод в секунды с учётом частоты кадров.
   */
  public double toSeconds(Double fps) {
    if (frame == null) {
      return seconds;
    }
    if (fps == null || fps <= 0) {
      throw new UserFacingException(
          "Не удалось определить частоту кадров, укажите таймкод без номера кадра");
    }
    if (frame >= Math.ceil(fps)) {
      throw new UserFacingException("В видео " + formatFps(fps)
          + " кадров в секунду, а в таймкоде «" + source + "» указан кадр " + frame
          + " (нумерация с 0)");
    }
    return seconds + frame / fps;
  }

  private static String formatFps(double fps) {
    return fps == Math.rint(fps) ? String.valueOf((long) fps) : String.format("%.3f", fps);
  }
}
