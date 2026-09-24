package ru.whitebeef.beefsavebot.service.download;

import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Трек защищён DRM: сервис не отдаёт его для скачивания.
 */
public class DrmProtectedException extends UserFacingException {

  public DrmProtectedException() {
    super("Этот трек защищён DRM — сервис не даёт его скачать :(");
  }
}
