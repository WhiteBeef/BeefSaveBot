package ru.whitebeef.beefsavebot.dto;

import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;

/**
 * @param quality        желаемое качество
 * @param audioOnly      нужен только звук (видео можно не скачивать, если площадка это позволяет)
 * @param maxSourceBytes максимальный размер скачиваемого исходника
 */
public record DownloadOptions(Quality quality, boolean audioOnly, long maxSourceBytes) {

  /**
   * Для обрезки и извлечения звука исходник можно брать больше итогового лимита: результат всё
   * равно получится меньше.
   */
  public static DownloadOptions of(Quality quality, OutputFormat format, boolean crop,
      DownloadConfiguration configuration) {
    return new DownloadOptions(quality, format.isAudioOnly(),
        crop || format.isAudioOnly() ? configuration.getSourceMaxBytes()
            : configuration.getMaxBytes());
  }
}
