package ru.whitebeef.beefsavebot.service;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Файл из сообщения (документ, фото, аудио, видео, голосовое…).
 */
@Getter
@AllArgsConstructor
public class IncomingFile {

  private final String fileId;
  private final String fileName;
  private final Long size;
}
