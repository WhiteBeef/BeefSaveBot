package ru.whitebeef.beefsavebot.service.download;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VideoDownloadService {

  private final List<DownloadService> youtubeDownloadServices;

  public File downloadVideo(String url) {
    return youtubeDownloadServices.stream()
        .filter(downloadService -> downloadService.canDownloadVideo(url))
        .findFirst()
        .map(downloadService -> downloadService.downloadVideo(url))
        .orElseThrow(() -> new RuntimeException(
            "Не могу обработать видео по ссылке " + url + " (обработчик не найден)"));
  }

  public boolean canDownloadVideo(String url) {
    return youtubeDownloadServices.stream()
        .anyMatch(downloadService -> downloadService.canDownloadVideo(url));
  }

  public String getSupportedSites() {
    AtomicInteger counter = new AtomicInteger(1);
    return youtubeDownloadServices.stream()
        .flatMap(downloadService -> downloadService.getSupportedSites().stream()
            .map(site -> counter.getAndIncrement() + ") " + site))
        .collect(Collectors.joining("\n"));
  }

}
