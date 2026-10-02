package ru.whitebeef.beefsavebot.dto;

import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;

/**
 * @param quality        желаемое качество
 * @param audioOnly      нужен только звук (видео можно не скачивать, если площадка это позволяет)
 * @param maxSourceBytes максимальный размер скачиваемого исходника
 * @param fitBytes       размер, в который желательно уложиться: варианты не больше него
 *                       выбираются в первую очередь (больший файл придётся резать на части)
 */
public record DownloadOptions(Quality quality, boolean audioOnly, long maxSourceBytes,
                              long fitBytes) {

  public DownloadOptions(Quality quality, boolean audioOnly, long maxSourceBytes) {
    this(quality, audioOnly, maxSourceBytes, maxSourceBytes);
  }

  /**
   * Исходник можно брать больше лимита Telegram: обрезанный фрагмент и звук получатся меньше,
   * а слишком большое видео отправится частями. Но если есть вариант, который влезает в одно
   * сообщение, выбираем его.
   */
  public static DownloadOptions of(Quality quality, OutputFormat format, boolean crop,
      DownloadConfiguration configuration) {
    return new DownloadOptions(quality, format.isAudioOnly(), configuration.getSourceMaxBytes(),
        crop || format.isAudioOnly() ? configuration.getSourceMaxBytes()
            : configuration.getMaxBytes());
  }
}
