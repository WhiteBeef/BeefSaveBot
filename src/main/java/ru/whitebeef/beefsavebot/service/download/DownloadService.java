package ru.whitebeef.beefsavebot.service.download;

import java.io.File;
import java.util.List;

public interface DownloadService {

  File downloadVideo(String url);

  boolean canDownloadVideo(String url);

  List<String> getSupportedSites();

}
