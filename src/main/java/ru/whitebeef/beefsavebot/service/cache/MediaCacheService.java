package ru.whitebeef.beefsavebot.service.cache;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.entity.MediaCacheEntry;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.repository.MediaCacheRepository;
import ru.whitebeef.beefsavebot.service.media.CropRange;

/**
 * Кэш отправленных файлов: ссылка + формат + качество (+ фрагмент) → file_id в Telegram.
 * Повторный запрос за время жизни кэша отдаётся мгновенно, без скачивания и перекодирования.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaCacheService {

  /**
   * Параметры ссылок, которые не влияют на содержимое: метки отслеживания и «поделиться».
   */
  private static final Set<String> TRACKING_PARAMS = Set.of("si", "feature", "igsh", "igshid",
      "_r", "_t", "is_from_webapp", "sender_device", "sender_web_id", "share_app_id",
      "share_link_id", "stkn", "ref", "pp", "utm_source", "utm_medium", "utm_campaign",
      "utm_term", "utm_content", "fbclid", "gclid");

  private final MediaCacheRepository repository;
  private final DownloadConfiguration downloadConfiguration;

  public boolean isEnabled() {
    return downloadConfiguration.getCacheHours() > 0;
  }

  /**
   * Ключ кэша для ссылки или трека с учётом всего, что влияет на результат.
   */
  public static String key(String source, OutputFormat format, Quality quality, CropRange crop) {
    StringBuilder key = new StringBuilder(normalize(source)).append('|').append(format)
        .append('|').append(quality);
    if (crop != null) {
      key.append('|').append(crop.start().source()).append('-').append(crop.end().source());
    }
    return key.length() > 1024 ? key.substring(0, 1024) : key.toString();
  }

  /**
   * Убирает из ссылки метки отслеживания и якорь, чтобы одна и та же ссылка из разных
   * приложений попадала в один элемент кэша.
   */
  static String normalize(String url) {
    String value = url.trim();
    int hash = value.indexOf('#');
    if (hash >= 0) {
      value = value.substring(0, hash);
    }
    int question = value.indexOf('?');
    if (question < 0) {
      return stripTrailingSlash(value);
    }
    String base = stripTrailingSlash(value.substring(0, question));
    String query = Arrays.stream(value.substring(question + 1).split("&"))
        .filter(param -> !param.isBlank())
        .filter(param -> {
          String name = param.split("=", 2)[0].toLowerCase(Locale.ROOT);
          return !TRACKING_PARAMS.contains(name) && !name.startsWith("utm_");
        })
        .sorted()
        .collect(Collectors.joining("&"));
    return query.isEmpty() ? base : base + "?" + query;
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  @Transactional(readOnly = true)
  public Optional<CachedMedia> find(String key) {
    if (!isEnabled() || key == null) {
      return Optional.empty();
    }
    LocalDateTime border = LocalDateTime.now().minusHours(downloadConfiguration.getCacheHours());
    return repository.findById(key)
        .filter(entry -> entry.getCreatedAt().isAfter(border))
        .map(entry -> new CachedMedia(entry.getFileId(), entry.getMediaKind(),
            entry.getFileSize()));
  }

  @Transactional
  public void put(String key, CachedMedia media) {
    if (!isEnabled() || key == null || media == null) {
      return;
    }
    repository.save(MediaCacheEntry.builder()
        .cacheKey(key)
        .fileId(media.fileId())
        .mediaKind(media.kind())
        .fileSize(media.fileSize())
        .createdAt(LocalDateTime.now())
        .build());
  }

  /**
   * Telegram не принял file_id (такое бывает редко) — забываем запись.
   */
  @Transactional
  public void evict(String key) {
    if (key != null) {
      repository.deleteById(key);
    }
  }

  @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
  @Transactional
  public void removeExpired() {
    int hours = Math.max(1, downloadConfiguration.getCacheHours());
    int removed = repository.deleteOlderThan(LocalDateTime.now().minusHours(hours));
    if (removed > 0) {
      log.info("Удалено устаревших записей кэша: {}", removed);
    }
  }
}
