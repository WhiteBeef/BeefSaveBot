package ru.whitebeef.beefsavebot.service.music;

import java.io.File;
import java.util.List;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.Quality;

/**
 * Поставщик музыки для поиска по названию. Чтобы подключить новый, достаточно объявить бин с этим
 * интерфейсом и добавить значение в {@link MusicProvider}.
 */
public interface MusicSearchProvider {

  MusicProvider provider();

  /**
   * Можно ли пользоваться поставщиком (например, задан ли токен).
   */
  default boolean isAvailable() {
    return true;
  }

  List<TrackResult> search(String query, int limit);

  /**
   * Скачивает трек из результатов поиска в MP3.
   */
  File download(TrackResult track, Quality quality);
}
