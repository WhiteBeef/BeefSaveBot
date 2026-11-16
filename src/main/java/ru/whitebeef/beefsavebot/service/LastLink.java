package ru.whitebeef.beefsavebot.service;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Последняя ссылка на видео в групповом чате.
 */
@Getter
@AllArgsConstructor
public class LastLink {

  private final String url;
  private final Integer messageId;
  private final Instant seenAt;
}
