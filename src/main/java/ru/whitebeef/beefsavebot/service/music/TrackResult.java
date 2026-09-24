package ru.whitebeef.beefsavebot.service.music;

import ru.whitebeef.beefsavebot.model.MusicProvider;

/**
 * Трек из результатов поиска.
 *
 * @param id идентификатор трека у поставщика (id в Яндекс Музыке, ссылка в SoundCloud)
 */
public record TrackResult(MusicProvider provider, String id, String artist, String title) {

  public String display() {
    return artist == null || artist.isBlank() ? title : artist + " - " + title;
  }
}
