package ru.whitebeef.beefsavebot.service.download;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TrackSearchResult {

  private final String trackId;
  private final String artist;
  private final String title;

  public String display() {
    return artist == null || artist.isBlank() ? title : artist + " - " + title;
  }
}
