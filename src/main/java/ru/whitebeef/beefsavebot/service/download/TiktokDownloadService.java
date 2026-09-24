package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.service.download.TiktokSlideshowFetcher.Slideshow;
import ru.whitebeef.beefsavebot.service.media.SlideshowBuilder;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

@Service
@Slf4j
public class TiktokDownloadService extends AbstractYtDlpDownloadService {

    private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
                    "^https:\\/\\/(www\\.)?(vm\\.|vt\\.)?tiktok\\.com\\/.+$")
            .asMatchPredicate();

    private final TiktokSlideshowFetcher slideshowFetcher;
    private final SlideshowBuilder slideshowBuilder;

    public TiktokDownloadService(DownloadConfiguration downloadConfiguration,
            YtDlpClient ytDlpClient, TiktokSlideshowFetcher slideshowFetcher,
            SlideshowBuilder slideshowBuilder) {
        super(downloadConfiguration, ytDlpClient);
        this.slideshowFetcher = slideshowFetcher;
        this.slideshowBuilder = slideshowBuilder;
    }

    /**
     * Фото-посты (слайд-шоу) yt-dlp не поддерживает: для них склеиваем видео из картинок
     * под музыку поста.
     */
    @Override
    public File downloadVideo(String url, DownloadOptions options) {
        String resolved = slideshowFetcher.resolve(url);
        if (TiktokSlideshowFetcher.isPhotoUrl(resolved)) {
            return downloadSlideshow(resolved, options);
        }
        try {
            return super.downloadVideo(url, options);
        } catch (UnsupportedUrlException e) {
            // Ссылку не удалось раскрыть заранее, но это может быть слайд-шоу
            return downloadSlideshow(resolved, options);
        }
    }

    private File downloadSlideshow(String url, DownloadOptions options) {
        log.info("Слайд-шоу TikTok: {}", url);
        Slideshow slideshow = slideshowFetcher.fetch(url);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ytdlp_slideshow_");
            Path music = null;
            if (slideshow.musicUrl() != null) {
                try {
                    music = dir.resolve("music.mp3");
                    slideshowFetcher.download(slideshow.musicUrl(), music);
                } catch (Exception e) {
                    log.warn("Не удалось скачать музыку слайд-шоу: {}", e.getMessage());
                    music = null;
                }
            }
            String baseName = fileNameBase(slideshow, url);
            if (options.audioOnly()) {
                if (music == null) {
                    throw new UserFacingException("В этом слайд-шоу нет музыки");
                }
                Path named = dir.resolve(baseName + ".mp3");
                Files.move(music, named);
                return named.toFile();
            }

            List<Path> images = new ArrayList<>();
            List<String> urls = slideshow.imageUrls();
            for (int i = 0; i < urls.size() && i < SlideshowBuilder.MAX_SLIDES; i++) {
                Path image = dir.resolve(String.format("slide_%03d%s", i, imageExtension(urls.get(i))));
                try {
                    slideshowFetcher.download(urls.get(i), image);
                    images.add(image);
                } catch (Exception e) {
                    log.warn("Не удалось скачать слайд {}: {}", i + 1, e.getMessage());
                }
            }
            if (images.isEmpty()) {
                throw new UserFacingException("Не удалось скачать картинки слайд-шоу");
            }
            File video = slideshowBuilder.build(images, music, options.quality(), dir, baseName);
            // Исходники больше не нужны, в директории остаётся только видео
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
            throw new RuntimeException("Не удалось собрать слайд-шоу: " + e.getMessage(), e);
        }
    }

    private static String fileNameBase(Slideshow slideshow, String url) {
        String id = TiktokSlideshowFetcher.postId(url);
        String fallback = "tiktok_" + (id != null ? id : UUID.randomUUID());
        String title = slideshow.title() == null ? "" : slideshow.title()
            .replaceAll("#\\S+", "").trim();
        return YtDlpClient.sanitizeFileName(title.length() > 60 ? title.substring(0, 60) : title,
            fallback);
    }

    private static String imageExtension(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".webp")) {
            return ".webp";
        }
        if (lower.contains(".png")) {
            return ".png";
        }
        if (lower.contains(".heic")) {
            return ".heic";
        }
        return ".jpg";
    }

    @Override
    protected boolean isCompatibleVideoCodec(String codec) {
        return codec.startsWith("h265") || codec.startsWith("h264");
    }

    @Override
    protected boolean isCompatibleAudioCodec(String codec) {
        return codec.startsWith("aac");
    }

    /**
     * Вертикальные видео ограничиваем по меньшей стороне.
     */
    @Override
    protected int dimensionOf(JsonNode format) {
        return Math.min(format.path("width").asInt(0), format.path("height").asInt(0));
    }

    @Override
    protected int maxDimension() {
        return downloadConfiguration.getMaxResolution();
    }

    @Override
    public boolean canDownloadVideo(String url) {
        return PATTERN_PREDICATE.test(url);
    }

    @Override
    public List<String> getSupportedSites() {
        return List.of("Tiktok (видео и слайд-шоу)");
    }
}
