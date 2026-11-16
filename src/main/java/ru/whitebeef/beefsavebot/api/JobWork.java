package ru.whitebeef.beefsavebot.api;

@FunctionalInterface
public interface JobWork {

  /** @return итог словами («Отправил видео 23 МБ») */
  String run() throws Exception;
}
