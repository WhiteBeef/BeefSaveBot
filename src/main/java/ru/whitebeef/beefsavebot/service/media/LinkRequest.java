package ru.whitebeef.beefsavebot.service.media;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ссылка из произвольного текста и, если после неё указаны два таймкода, фрагмент для обрезки:
 * {@code "глянь https://youtu.be/xxx 0:10 0:25"}.
 *
 * @param crop фрагмент или {@code null}
 */
public record LinkRequest(String url, CropRange crop) {

  private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

  /**
   * @return запрос или {@code null}, если ссылки в тексте нет
   * @throws UserFacingException если таймкоды указаны, но записаны с ошибкой
   */
  public static LinkRequest parse(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = URL.matcher(text);
    if (!matcher.find()) {
      return null;
    }
    String url = matcher.group();
    String[] rest = Arrays.stream(text.substring(matcher.end()).trim().split("\\s+"))
        .filter(token -> !token.isBlank())
        .toArray(String[]::new);
    CropRange crop = null;
    if (rest.length >= 2 && looksLikeTimeCode(rest[0]) && looksLikeTimeCode(rest[1])) {
      crop = new CropRange(TimeCode.parse(rest[0]), TimeCode.parse(rest[1]));
    } else if (rest.length >= 1 && rest[0].matches("[\\d:.,;]+-[\\d:.,;]+")) {
      String[] range = rest[0].split("-");
      crop = new CropRange(TimeCode.parse(range[0]), TimeCode.parse(range[1]));
    }
    if (crop != null && crop.start().frame() == null && crop.end().frame() == null
        && crop.end().seconds() <= crop.start().seconds()) {
      throw new UserFacingException("Конец фрагмента должен быть позже начала");
    }
    return new LinkRequest(url, crop);
  }

  private static boolean looksLikeTimeCode(String token) {
    return token.matches("[\\d:.,;]+") && Character.isDigit(token.charAt(0));
  }
}
