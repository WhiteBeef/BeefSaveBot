package ru.whitebeef.beefsavebot.service.download;

import java.io.File;
import java.util.List;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;

public interface DownloadService {

  File downloadVideo(String url, DownloadOptions options);

  boolean canDownloadVideo(String url);

  List<String> getSupportedSites();

  default MediaType getMediaType() {
    return MediaType.VIDEO;
  }

}
