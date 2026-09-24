package ru.whitebeef.beefsavebot.service.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.model.Quality;

class SlideshowBuilderTest {

  @TempDir
  Path tempDir;
  private SlideshowBuilder builder;

  @BeforeEach
  void setUp() {
    assumeTrue(commandAvailable(), "ffmpeg не установлен");
    DownloadConfiguration configuration = new DownloadConfiguration();
    configuration.setSlideSeconds(3);
    configuration.setFfmpegTimeoutMinutes(2);
    builder = new SlideshowBuilder(configuration);
  }

  @Test
  void buildsVerticalVideoWithLoopedMusic() throws Exception {
    List<Path> images = List.of(
        image("portrait.jpg", "720x1280", "red"),
        image("landscape.webp", "1280x720", "green"),
        image("square.png", "800x800", "blue"));
    // Музыка короче слайдов — должна зациклиться
    Path audio = tempDir.resolve("music.mp3");
    run(List.of("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=duration=2",
        audio.toString()));

    File video = builder.build(images, audio, Quality.MEDIUM, tempDir, "Слайды");

    JsonNode probe = probe(video);
    JsonNode videoStream = probe.path("streams").get(0);
    assertEquals(720, videoStream.path("width").asInt());
    assertEquals(1280, videoStream.path("height").asInt());
    assertEquals("audio", probe.path("streams").get(1).path("codec_type").asText());
    // 3 слайда по 3 с минус 2 перехода по 0.5 с
    assertEquals(8.0, probe.path("format").path("duration").asDouble(), 0.2);
  }

  @Test
  void worksWithSingleImageAndNoMusic() throws Exception {
    File video = builder.build(List.of(image("one.jpg", "640x640", "white")), null,
        Quality.LOW, tempDir, "one");
    JsonNode probe = probe(video);
    assertEquals(1, probe.path("streams").size());
    assertEquals(3.0, probe.path("format").path("duration").asDouble(), 0.2);
  }

  private Path image(String name, String size, String color) throws Exception {
    Path file = tempDir.resolve(name);
    run(List.of("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i",
        "color=c=" + color + ":s=" + size, "-frames:v", "1", file.toString()));
    return file;
  }

  private static JsonNode probe(File file) throws Exception {
    return new ObjectMapper().readTree(run(List.of("ffprobe", "-v", "error", "-print_format",
        "json", "-show_format", "-show_streams", file.getAbsolutePath())));
  }

  private static String run(List<String> command) throws Exception {
    Process process = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    return output;
  }

  private static boolean commandAvailable() {
    try {
      return new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
