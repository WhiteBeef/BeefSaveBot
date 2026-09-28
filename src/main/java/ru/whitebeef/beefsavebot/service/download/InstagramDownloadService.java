package ru.whitebeef.beefsavebot.service.download;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

@Service
@Slf4j
public class InstagramDownloadService extends AbstractYtDlpDownloadService {

  private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
          "^(?:https?://)?(?:www\\.)?instagram\\.com/(?:p|reel|reels|tv)/[\\w-]+/?(?:\\?.*)?$")
      .asMatchPredicate();

  private final InstagramDirectFetcher directFetcher;

  public InstagramDownloadService(DownloadConfiguration downloadConfiguration,
      YtDlpClient ytDlpClient, InstagramDirectFetcher directFetcher) {
    super(downloadConfiguration, ytDlpClient);
    this.directFetcher = directFetcher;
  }

  /**
   * Instagram часто отказывает анонимным запросам с серверных IP (HTTP 400/401/429). Тогда
   * пробуем yt-dlp под видом браузера, а затем — прямую ссылку со страницы встраивания.
   */
  @Override
  public File downloadVideo(String url, DownloadOptions options) {
    RuntimeException firstError;
    try {
      return super.downloadVideo(url, options);
    } catch (UserFacingException e) {
      throw e;
    } catch (RuntimeException e) {
      firstError = e;
      log.warn("yt-dlp не скачал {}: {}. Пробую под видом браузера", url, e.getMessage());
    }
    try {
      return super.downloadVideo(url, options, List.of("--impersonate", "chrome"));
    } catch (UserFacingException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("yt-dlp с impersonation не скачал {}: {}. Пробую напрямую", url, e.getMessage());
    }
    File direct = downloadDirectly(url, options);
    if (direct != null) {
      return direct;
    }
    log.error("Instagram не отдал {} ни одним способом. Если так со всеми ссылками — добавьте "
        + "cookies (DOWNLOAD_YT_DLP_COOKIES_FILE, см. README)", url, firstError);
    throw new UserFacingException("Instagram не отдал это видео — попробуйте позже");
  }

  private File downloadDirectly(String url, DownloadOptions options) {
    String videoUrl = directFetcher.findVideoUrl(url);
    if (videoUrl == null) {
      return null;
    }
    Path dir = null;
    try {
      dir = Files.createTempDirectory("ytdlp_instagram_");
      String code = InstagramDirectFetcher.shortcode(url);
      Path target = dir.resolve("instagram_" + (code == null ? "video" : code) + ".mp4");
      directFetcher.download(videoUrl, target);
      if (Files.size(target) > options.maxSourceBytes()) {
        throw new UserFacingException("Видео больше "
            + options.maxSourceBytes() / 1024 / 1024 + " МБ :(");
      }
      return target.toFile();
    } catch (UserFacingException e) {
      YtDlpClient.deleteDirectory(dir);
      throw e;
    } catch (Exception e) {
      log.warn("Не удалось скачать видео Instagram напрямую: {}", e.getMessage());
      YtDlpClient.deleteDirectory(dir);
      return null;
    }
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
