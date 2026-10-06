package ru.whitebeef.beefsavebot.service.download;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.service.media.SlideshowBuilder;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

@Service
@Slf4j
public class InstagramDownloadService extends AbstractYtDlpDownloadService {

  /**
   * Сколько длится видео из фото-поста, если длина музыки неизвестна.
   */
  private static final double DEFAULT_MUSIC_SECONDS = 30;

  private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
          "^(?:https?://)?(?:www\\.)?instagram\\.com/(?:p|reel|reels|tv)/[\\w-]+/?(?:\\?.*)?$")
      .asMatchPredicate();

  private static final Pattern POST_URL = Pattern.compile("instagram\\.com/p/");

  private final InstagramDirectFetcher directFetcher;
  private final InstagramPostFetcher postFetcher;
  private final SlideshowBuilder slideshowBuilder;

  public InstagramDownloadService(DownloadConfiguration downloadConfiguration,
      YtDlpClient ytDlpClient, InstagramDirectFetcher directFetcher,
      InstagramPostFetcher postFetcher, SlideshowBuilder slideshowBuilder) {
    super(downloadConfiguration, ytDlpClient);
    this.directFetcher = directFetcher;
    this.postFetcher = postFetcher;
    this.slideshowBuilder = slideshowBuilder;
  }

  /**
   * Фото-посты (одна картинка или карусель, часто под музыку) склеиваются в видео, как
   * слайд-шоу TikTok. Видео качает yt-dlp; Instagram часто отказывает анонимным запросам с
   * серверных IP (HTTP 400/401/429), тогда пробуем yt-dlp под видом браузера, а затем — прямую
   * ссылку со страницы встраивания.
   */
  @Override
  public File downloadVideo(String url, DownloadOptions options) {
    // Фото бывают только в постах (/p/): их состав узнаём сразу, ведь видео yt-dlp там не найдёт
    InstagramPostFetcher.Post post = POST_URL.matcher(url).find() ? postFetcher.fetch(url)
        : null;
    if (post != null && post.isPhotoPost()) {
      return downloadSlideshow(url, post, options);
    }
    RuntimeException firstError;
    try {
      return super.downloadVideo(url, options);
    } catch (UserFacingException e) {
      throw e;
    } catch (RuntimeException e) {
      firstError = e;
      log.warn("yt-dlp не скачал {}: {}", url, e.getMessage());
    }
    if (post == null) {
      post = postFetcher.fetch(url);
      if (post != null && post.isPhotoPost()) {
        return downloadSlideshow(url, post, options);
      }
    }
    if (post != null && post.firstVideoUrl() != null) {
      File file = downloadFromUrl(url, post.firstVideoUrl(), options);
      if (file != null) {
        return file;
      }
    }
    log.warn("Пробую скачать {} под видом браузера", url);
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
    return videoUrl == null ? null : downloadFromUrl(url, videoUrl, options);
  }

  private File downloadFromUrl(String url, String videoUrl, DownloadOptions options) {
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
   * Склеивает картинки поста в видео под его музыку. Если картинка одна, а музыка длинная,
   * видео длится столько, сколько музыка играет в посте.
   */
  private File downloadSlideshow(String url, InstagramPostFetcher.Post post,
      DownloadOptions options) {
    log.info("Фото-пост Instagram: {} картинок, музыка: {}", post.imageUrls().size(),
        post.music() != null);
    Path dir = null;
    try {
      dir = Files.createTempDirectory("ytdlp_slideshow_");
      String baseName = fileNameBase(post, url);
      InstagramPostFetcher.Music musicInfo = post.music();
      Path music = null;
      if (musicInfo != null) {
        try {
          music = dir.resolve("music.m4a");
          directFetcher.download(musicInfo.url(), music);
        } catch (Exception e) {
          log.warn("Не удалось скачать музыку поста: {}", e.getMessage());
          music = null;
        }
      }
      double start = musicInfo == null || musicInfo.startMs() == null ? 0
          : musicInfo.startMs() / 1000.0;
      if (options.audioOnly()) {
        if (music == null) {
          throw new UserFacingException("В этом посте нет музыки");
        }
        // В MP3 переведёт MediaProcessingService; здесь только вырезаем звучащий в посте кусок
        return trimMusic(music, start, musicInfo, dir.resolve(baseName + ".m4a"));
      }

      List<Path> images = new ArrayList<>();
      List<String> urls = post.imageUrls();
      for (int i = 0; i < urls.size() && i < SlideshowBuilder.MAX_SLIDES; i++) {
        Path image = dir.resolve(String.format("slide_%03d.jpg", i));
        try {
          directFetcher.download(urls.get(i), image);
          images.add(image);
        } catch (Exception e) {
          log.warn("Не удалось скачать картинку {}: {}", i + 1, e.getMessage());
        }
      }
      if (images.isEmpty()) {
        throw new UserFacingException("Не удалось скачать картинки из поста");
      }
      Double target = music == null ? null : musicInfo.durationMs() != null
          ? musicInfo.durationMs() / 1000.0 : DEFAULT_MUSIC_SECONDS;
      File video = slideshowBuilder.build(images, music, start, target, options.quality(), dir,
          baseName);
      for (Path image : images) {
        Files.deleteIfExists(image);
      }
      if (music != null) {
        Files.deleteIfExists(music);
      }
      return video;
    } catch (UserFacingException e) {
      YtDlpClient.deleteDirectory(dir);
      throw e;
    } catch (IOException | InterruptedException | RuntimeException e) {
      YtDlpClient.deleteDirectory(dir);
      throw new RuntimeException("Не удалось собрать видео из фото-поста: " + e.getMessage(), e);
    }
  }

  private File trimMusic(Path music, double start, InstagramPostFetcher.Music info,
      Path target) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel",
        "error", "-y", "-ss", String.format(Locale.ROOT, "%.3f", start), "-i",
        music.toString()));
    if (info.durationMs() != null && info.durationMs() > 0) {
      command.addAll(List.of("-t",
          String.format(Locale.ROOT, "%.3f", info.durationMs() / 1000.0)));
    }
    // Исполнителя и название трека Telegram покажет в плеере
    if (info.title() != null) {
      command.addAll(List.of("-metadata", "title=" + info.title()));
    }
    if (info.artist() != null) {
      command.addAll(List.of("-metadata", "artist=" + info.artist()));
    }
    command.addAll(List.of("-vn", "-c:a", "aac", "-b:a", "192k", target.toString()));
    Process process = new ProcessBuilder(command).inheritIO().start();
    if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0
        || !Files.exists(target)) {
      process.destroyForcibly();
      throw new IOException("ffmpeg не смог вырезать музыку поста");
    }
    Files.deleteIfExists(music);
    return target.toFile();
  }

  private static String fileNameBase(InstagramPostFetcher.Post post, String url) {
    String code = InstagramDirectFetcher.shortcode(url);
    String fallback = "instagram_" + (code == null ? UUID.randomUUID() : code);
    String title = post.title() == null ? "" : post.title().lines().findFirst().orElse("")
        .replaceAll("#\\S+", "").trim();
    return YtDlpClient.sanitizeFileName(title.length() > 60 ? title.substring(0, 60) : title,
        fallback);
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
    return List.of("Instagram (видео и фото-посты с музыкой)");
  }
}
