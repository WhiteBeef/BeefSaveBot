package ru.whitebeef.beefsavebot.service.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
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
}
