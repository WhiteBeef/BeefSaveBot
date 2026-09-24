package ru.whitebeef.beefsavebot.service.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;

/**
 * Прогоняет настоящий ffmpeg. Пропускается, если ffmpeg не установлен.
 */
class MediaProcessingServiceTest {

  private static final int FPS = 25;

  @TempDir
  static Path tempDir;
  private static File source;
  private static MediaProcessingService service;

  @BeforeAll
  static void setUp() throws Exception {
    assumeTrue(commandAvailable("ffmpeg") && commandAvailable("ffprobe"), "ffmpeg не установлен");
    // Яркость кадра N равна 2*N — по ней проверяем, с какого кадра начался фрагмент
    source = tempDir.resolve("Test video.mp4").toFile();
    run(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "nullsrc=s=64x64:r=" + FPS + ":d=4,geq=lum='2*N':cb=128:cr=128",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
        "-c:v", "libx264", "-qp", "0", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
        source.getAbsolutePath()));
    DownloadConfiguration configuration = new DownloadConfiguration();
    configuration.setMaxBytes(50L * 1024 * 1024);
    configuration.setFfmpegTimeoutMinutes(2);
    service = new MediaProcessingService(configuration);
  }

  @Test
  void returnsSourceWhenNothingToDo() throws Exception {
    assertSame(source, service.process(source, OutputFormat.MP4, Quality.HIGH, null));
  }

  @Test
  void cropsWithFrameAccuracy() throws Exception {
    CropRange range = new CropRange(TimeCode.parse("0:00:01:05"), TimeCode.parse("0:00:02:10"));
    File result = service.process(source, OutputFormat.MP4, Quality.HIGH, range);

    assertEquals("Test video.mp4", result.getName());
    // 1с+5к = кадр 30, 2с+10к = кадр 60 → 30 кадров
    assertEquals(30, countFrames(result));
    assertEquals(60, firstFrameLuma(result), 3);
  }

  @Test
  void convertsToMp3() throws Exception {
    File result = service.process(source, OutputFormat.MP3, Quality.LOW, null);
    JsonNode probe = probe(result);
    assertEquals("Test video.mp3", result.getName());
    assertEquals("mp3", probe.path("streams").get(0).path("codec_name").asText());
    assertEquals(1, probe.path("streams").size());
    assertEquals("Test video", probe.path("format").path("tags").path("title").asText());
  }

  @Test
  void convertsToWebm() throws Exception {
    File result = service.process(source, OutputFormat.WEBM, Quality.MEDIUM, null);
    JsonNode probe = probe(result);
    List<String> codecs = new ArrayList<>();
    probe.path("streams").forEach(stream -> codecs.add(stream.path("codec_name").asText()));
    assertEquals(List.of("vp9", "opus"), codecs);
  }

  @Test
  void convertsToAnimatedWebp() throws Exception {
    File result = service.process(source, OutputFormat.WEBP, Quality.LOW,
        new CropRange(TimeCode.parse("1"), TimeCode.parse("2")));
    byte[] bytes = Files.readAllBytes(result.toPath());
    String header = new String(bytes, 0, 16, StandardCharsets.US_ASCII);
    assertTrue(header.startsWith("RIFF") && header.contains("WEBP"), header);
    // Анимированный WebP содержит чанк ANIM
    assertTrue(new String(bytes, StandardCharsets.US_ASCII).contains("ANIM"));
  }

  @Test
  void rejectsRangeOutsideOfVideo() {
    CropRange range = new CropRange(TimeCode.parse("10"), TimeCode.parse("12"));
    UserFacingException exception = org.junit.jupiter.api.Assertions.assertThrows(
        UserFacingException.class,
        () -> service.process(source, OutputFormat.MP4, Quality.HIGH, range));
    assertTrue(exception.getMessage().contains("0:04"), exception.getMessage());
  }

  private static int countFrames(File file) throws Exception {
    String output = run(List.of("ffprobe", "-v", "error", "-count_frames", "-select_streams",
        "v:0", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0",
        file.getAbsolutePath()));
    return Integer.parseInt(output.trim());
  }

  private static double firstFrameLuma(File file) throws Exception {
    String output = run(List.of("ffprobe", "-v", "error", "-f", "lavfi",
        "movie=" + file.getAbsolutePath().replace(" ", "\\ ") + ",signalstats",
        "-read_intervals", "%+#1", "-show_entries", "frame_tags=lavfi.signalstats.YAVG",
        "-of", "csv=p=0"));
    return Double.parseDouble(output.trim().split("\n")[0].replace(",", "").trim());
  }

  private static JsonNode probe(File file) throws Exception {
    return new ObjectMapper().readTree(run(List.of("ffprobe", "-v", "error", "-print_format",
        "json", "-show_format", "-show_streams", file.getAbsolutePath())));
  }

  private static boolean commandAvailable(String command) {
    try {
      return new ProcessBuilder(command, "-version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static String run(List<String> command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    return output;
  }
}
