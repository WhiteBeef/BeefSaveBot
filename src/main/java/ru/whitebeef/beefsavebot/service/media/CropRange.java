package ru.whitebeef.beefsavebot.service.media;

public record CropRange(TimeCode start, TimeCode end) {

  @Override
  public String toString() {
    return start.source() + " — " + end.source();
  }
}
