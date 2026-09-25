package ru.whitebeef.beefsavebot.service.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.entity.MediaCacheEntry;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.repository.MediaCacheRepository;
import ru.whitebeef.beefsavebot.service.media.CropRange;
import ru.whitebeef.beefsavebot.service.media.TimeCode;

class MediaCacheServiceTest {

  @Test
  void trackingParametersDoNotSplitCache() {
    assertEquals("https://youtu.be/dQw4w9WgXcQ",
        MediaCacheService.normalize("https://youtu.be/dQw4w9WgXcQ?si=abcdef"));
    assertEquals("https://www.instagram.com/reel/DdoLnWEI9dC",
        MediaCacheService.normalize(
            "https://www.instagram.com/reel/DdoLnWEI9dC/?stkn=x&igsh=y&utm_source=ig"));
    assertEquals("https://www.tiktok.com/@u/video/1",
        MediaCacheService.normalize("https://www.tiktok.com/@u/video/1?_r=1&_t=ZS-9#comments"));
    // Значимые параметры сохраняются и сортируются
    assertEquals("https://www.youtube.com/watch?t=10&v=dQw4w9WgXcQ",
        MediaCacheService.normalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10&feature=share"));
  }

  @Test
  void keyDependsOnFormatQualityAndCrop() {
    String url = "https://youtu.be/dQw4w9WgXcQ";
    String base = MediaCacheService.key(url, OutputFormat.MP4, Quality.HIGH, null);
    assertEquals(base, MediaCacheService.key(url + "?si=1", OutputFormat.MP4, Quality.HIGH,
        null));
    assertNotEquals(base, MediaCacheService.key(url, OutputFormat.MP3, Quality.HIGH, null));
    assertNotEquals(base, MediaCacheService.key(url, OutputFormat.MP4, Quality.LOW, null));
    CropRange crop = new CropRange(TimeCode.parse("0:10"), TimeCode.parse("0:20"));
    assertNotEquals(base, MediaCacheService.key(url, OutputFormat.MP4, Quality.HIGH, crop));
  }

  private static MediaCacheService service(boolean enabled, int hours, MediaCacheEntry entry,
      MediaCacheRepository repository) {
    DownloadConfiguration configuration = new DownloadConfiguration();
    configuration.setCacheEnabled(enabled);
    configuration.setCacheHours(hours);
    when(repository.findById("k")).thenReturn(Optional.ofNullable(entry));
    return new MediaCacheService(repository, configuration);
  }

  private static MediaCacheEntry entryCreated(LocalDateTime createdAt) {
    return MediaCacheEntry.builder().cacheKey("k").fileId("file")
        .mediaKind(CachedMedia.Kind.VIDEO).createdAt(createdAt).build();
  }

  @Test
  void entriesNeverExpireByDefault() {
    MediaCacheRepository repository = mock(MediaCacheRepository.class);
    MediaCacheService cache = service(true, 0,
        entryCreated(LocalDateTime.now().minusDays(365)), repository);

    assertTrue(cache.find("k").isPresent());
    cache.removeExpired();
    verify(repository, never()).deleteOlderThan(any());
  }

  @Test
  void entriesExpireWhenLifetimeIsSet() {
    MediaCacheRepository repository = mock(MediaCacheRepository.class);
    MediaCacheService cache = service(true, 24,
        entryCreated(LocalDateTime.now().minusHours(25)), repository);

    assertTrue(cache.find("k").isEmpty());
    cache.removeExpired();
    verify(repository).deleteOlderThan(any());
  }

  @Test
  void disabledCacheIsNotUsed() {
    MediaCacheRepository repository = mock(MediaCacheRepository.class);
    MediaCacheService cache = service(false, 0, entryCreated(LocalDateTime.now()), repository);

    assertTrue(cache.find("k").isEmpty());
    cache.put("k", new CachedMedia("file", CachedMedia.Kind.VIDEO, 1L));
    verify(repository, never()).save(any());
  }
}
