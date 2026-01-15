package ru.whitebeef.beefsavebot.configuration;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
public class DownloadConfiguration {

  @Value("${download.max-bytes:52428800}")
  private long maxBytes;

  @Value("${download.max-height:1080}")
  private int maxHeight;

  @Value("${download.max-resolution:1080}")
  private int maxResolution;

  @Value("${download.yt-dlp.user-agent:}")
  private String ytDlpUserAgent;
}
