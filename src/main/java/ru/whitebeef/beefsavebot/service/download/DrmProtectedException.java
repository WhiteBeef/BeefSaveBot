package ru.whitebeef.beefsavebot.service.download;

import lombok.Getter;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Трек защищён DRM: сервис не отдаёт его для скачивания.
 */
@Getter
public class DrmProtectedException extends UserFacingException {

  /**
   * «Исполнитель Название», если известно: по нему можно найти трек в других сервисах.
   */
  private final String trackName;
  private final MusicProvider provider;

  public DrmProtectedException() {
    this(null, null);
  }

  public DrmProtectedException(String trackName, MusicProvider provider) {
    super("Этот трек защищён DRM — " + (provider == null ? "сервис" : provider.getTitle())
        + " не даёт его скачать :(");
    this.trackName = trackName;
    this.provider = provider;
  }
}
