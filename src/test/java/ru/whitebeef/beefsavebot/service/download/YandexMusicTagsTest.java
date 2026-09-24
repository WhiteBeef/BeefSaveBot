package ru.whitebeef.beefsavebot.service.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.service.download.YandexMusicDownloadService.TrackMeta;
import ru.whitebeef.beefsavebot.service.media.MediaProcessingService;
import ru.whitebeef.beefsavebot.service.media.MediaProcessingService.AudioTags;

/**
 * Теги треков: запись через ffmpeg и чтение для отправки в Telegram. Нужен ffmpeg.
 */
class YandexMusicTagsTest {

  @TempDir
  Path tempDir;
  private YandexMusicDownloadService yandex;
  private MediaProcessingService media;

  @BeforeEach
  void setUp() {
    assumeTrue(commandAvailable("ffmpeg"), "ffmpeg не установлен");
    DownloadConfiguration configuration = new DownloadConfiguration();
    yandex = new YandexMusicDownloadService(configuration);
    media = new MediaProcessingService(configuration);
  }

  @Test
  void overwritesExistingTagsWithId3v23() throws Exception {
    // Файл уже с ID3v2.4-тегом от «другого» исполнителя
    File file = mp3("Кино - Группа крови.mp3", List.of("-id3v2_version", "4",
        "-metadata", "artist=Old", "-metadata", "title=Old title"));

    yandex.writeId3Tags(file, new TrackMeta("Кино", "Группа крови", "Группа крови"));

    byte[] header = Files.readAllBytes(file.toPath());
    assertEquals("ID3", new String(header, 0, 3));
    assertEquals(3, header[3], "ожидается ID3v2.3");
    AudioTags tags = media.readAudioTags(file);
    assertEquals("Кино", tags.performer());
    assertEquals("Группа крови", tags.title());
  }

  @Test
  void fallsBackToFileNameWithoutTags() throws Exception {
    File file = mp3("Queen - Bohemian Rhapsody.mp3", List.of("-map_metadata", "-1",
        "-write_id3v1", "0", "-id3v2_version", "0"));
    AudioTags tags = media.readAudioTags(file);
    assertEquals("Queen", tags.performer());
    assertEquals("Bohemian Rhapsody", tags.title());

    File noArtist = mp3("Just a title.mp3", List.of("-map_metadata", "-1"));
    assertNull(media.readAudioTags(noArtist).performer());
    assertEquals("Just a title", media.readAudioTags(noArtist).title());
  }

  private File mp3(String name, List<String> extraArgs) throws Exception {
    File file = tempDir.resolve(name).toFile();
    List<String> command = new java.util.ArrayList<>(List.of("ffmpeg", "-loglevel", "error",
        "-y", "-f", "lavfi", "-i", "sine=duration=1", "-c:a", "libmp3lame"));
    command.addAll(extraArgs);
    command.add(file.getAbsolutePath());
    assertEquals(0, new ProcessBuilder(command).inheritIO().start().waitFor());
    return file;
  }

  private static boolean commandAvailable(String command) {
    try {
      return new ProcessBuilder(command, "-version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
