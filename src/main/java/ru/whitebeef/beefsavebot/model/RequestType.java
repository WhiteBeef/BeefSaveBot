package ru.whitebeef.beefsavebot.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RequestType {
  DOWNLOAD("Скачивание"),
  CROP("Обрезка"),
  SEARCH("Поиск"),
  TRACK("Трек из поиска"),
  COMMAND("Команда");

  private final String title;
}
