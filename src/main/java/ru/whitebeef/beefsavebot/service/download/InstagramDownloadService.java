package ru.whitebeef.beefsavebot.service.download;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;

@Service
@Slf4j
public class InstagramDownloadService extends AbstractYtDlpDownloadService {

  private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
          "^(?:https?://)?(?:www\\.)?instagram\\.com/(?:p|reel|reels|tv)/[\\w-]+/?(?:\\?.*)?$")
      .asMatchPredicate();

  public InstagramDownloadService(DownloadConfiguration downloadConfiguration,
      YtDlpClient ytDlpClient) {
    super(downloadConfiguration, ytDlpClient);
  }

  /**
   * Только то, что воспроизводится на iPhone: VP9/AV1 и Opus в MP4 там не играют. Если других
   * вариантов нет, выбор отдаётся yt-dlp, а перед отправкой файл всё равно перекодируется.
   */
  @Override
  protected boolean isCompatibleVideoCodec(String codec) {
    return codec.startsWith("avc1") || codec.startsWith("h264") || codec.startsWith("h265")
        || codec.startsWith("hvc1") || codec.startsWith("hev1") || codec.startsWith("hevc");
  }

  @Override
  protected boolean isCompatibleAudioCodec(String codec) {
    return codec.startsWith("mp4a") || codec.startsWith("aac");
  }

  @Override
  protected List<String> extraArgs() {
    List<String> args = new ArrayList<>();
    String userAgent = downloadConfiguration.getYtDlpUserAgent();
    if (userAgent != null && !userAgent.isBlank()) {
      args.add("--user-agent");
      args.add(userAgent);
    }
    String cookiesFile = downloadConfiguration.getYtDlpCookiesFile();
    if (cookiesFile != null && !cookiesFile.isBlank()) {
      args.add("--cookies");
      args.add(cookiesFile);
    }
    return args;
  }

  @Override
  public boolean canDownloadVideo(String url) {
    return PATTERN_PREDICATE.test(url);
  }

  @Override
  public List<String> getSupportedSites() {
    return List.of("Instagram video");
  }
}
