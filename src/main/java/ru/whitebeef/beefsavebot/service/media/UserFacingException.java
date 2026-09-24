package ru.whitebeef.beefsavebot.service.media;

/**
 * Ошибка, текст которой можно показать пользователю как есть.
 */
public class UserFacingException extends RuntimeException {

  public UserFacingException(String message) {
    super(message);
  }
}
