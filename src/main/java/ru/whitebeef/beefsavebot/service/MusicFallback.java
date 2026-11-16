package ru.whitebeef.beefsavebot.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.whitebeef.beefsavebot.model.MusicProvider;

/**
 * Трек, который при неудаче можно поискать в других сервисах.
 */
@Getter
@AllArgsConstructor
public class MusicFallback {

  private final String query;
  private final MusicProvider provider;
}
