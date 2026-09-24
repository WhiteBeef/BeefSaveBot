package ru.whitebeef.beefsavebot.service.download;

import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * yt-dlp не знает, как скачивать по этой ссылке.
 */
public class UnsupportedUrlException extends UserFacingException {

  public UnsupportedUrlException() {
    super("Не умею скачивать по этой ссылке :(");
  }
}
