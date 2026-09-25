package ru.whitebeef.beefsavebot.configuration;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
public class DownloadConfiguration {

  @Value("${download.max-bytes:52428800}")
  private long maxBytes;

  /**
   * Максимальный размер исходника, который можно скачать для обрезки или конвертации
   * (итоговый файл всё равно должен уложиться в max-bytes).
   */
  @Value("${download.source-max-bytes:524288000}")
  private long sourceMaxBytes;

  /**
   * Кэш file_id отправленных файлов для мгновенной повторной отправки.
   */
  @Value("${download.cache-enabled:true}")
  private boolean cacheEnabled;

  /**
   * Сколько часов хранить запись кэша. 0 — бессрочно: файлы лежат в Telegram, а у нас только
   * их file_id.
   */
  @Value("${download.cache-hours:0}")
  private int cacheHours;

  @Value("${download.ffmpeg-timeout-minutes:15}")
  private long ffmpegTimeoutMinutes;

  /**
   * Сколько секунд показывается каждый слайд в видео из слайд-шоу TikTok.
   */
  @Value("${download.slideshow.seconds-per-slide:3}")
  private double slideSeconds;

  /**
   * Запасной сторонний API (tikwm.com) для слайд-шоу TikTok, если не удалось разобрать страницу.
   */
  @Value("${download.slideshow.fallback-api-enabled:true}")
  private boolean slideshowFallbackApiEnabled;

  @Value("${download.max-height:1080}")
  private int maxHeight;

  @Value("${download.max-resolution:1080}")
  private int maxResolution;

  @Value("${download.yt-dlp.user-agent:}")
  private String ytDlpUserAgent;

  @Value("${download.yt-dlp.cookies-file:}")
  private String ytDlpCookiesFile;

  @Value("${download.yandex-music.token:}")
  private String yandexMusicToken;
}
