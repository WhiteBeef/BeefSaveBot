package ru.whitebeef.beefsavebot.service.convert.converters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.convert.CommandRunner;
import ru.whitebeef.beefsavebot.service.convert.ConversionJob;
import ru.whitebeef.beefsavebot.service.convert.FileConverter;

/**
 * Аудио и видео через ffmpeg.
 */
@Order(10)
@Component
@RequiredArgsConstructor
public class FfmpegFileConverter implements FileConverter {

  private static final List<String> AUDIO_IN = List.of("mp3", "wav", "ogg", "opus", "flac", "m4a",
      "aac", "wma", "aiff", "amr", "ac3");
  private static final List<String> VIDEO_IN = List.of("mp4", "mkv", "webm", "avi", "mov", "flv",
      "wmv", "mpg", "ts", "3gp", "m4v");
  private static final List<String> AUDIO_OUT = List.of("mp3", "wav", "ogg", "opus", "flac",
      "m4a", "aac", "aiff");
  private static final List<String> VIDEO_OUT = List.of("mp4", "mkv", "webm", "avi", "mov",
      "gif", "webp");
  /**
   * Приводит размеры к чётным — этого требует H.264.
   */
  private static final String EVEN_SIZE = "scale=trunc(iw/2)*2:trunc(ih/2)*2";

  private final CommandRunner commandRunner;

  @Override
  public Map<String, Set<String>> conversions() {
    return new ConversionMatrix()
        .add(AUDIO_IN, AUDIO_OUT)
        .add(VIDEO_IN, AUDIO_OUT)
        .add(VIDEO_IN, VIDEO_OUT)
        .add(List.of("gif"), List.of("mp4", "webm", "mkv", "mov"))
        .build();
  }

  @Override
  public boolean isAvailable() {
    return commandRunner.exists("ffmpeg");
  }

  @Override
  public Path convert(ConversionJob job) throws Exception {
    Path output = job.defaultOutput();
    List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel",
        "error", "-y", "-i", job.input().toString()));
    command.addAll(encoderArgs(job.targetFormat()));
    command.add(output.toString());
    commandRunner.run(command, Duration.ofMinutes(15));
    return output;
  }

  private List<String> encoderArgs(String target) {
    return switch (target) {
      case "mp3" -> List.of("-vn", "-c:a", "libmp3lame", "-q:a", "2");
      case "wav" -> List.of("-vn", "-c:a", "pcm_s16le");
      case "aiff" -> List.of("-vn", "-c:a", "pcm_s16be");
      case "ogg" -> List.of("-vn", "-c:a", "libvorbis", "-q:a", "5");
      case "opus" -> List.of("-vn", "-c:a", "libopus", "-b:a", "128k");
      case "flac" -> List.of("-vn", "-c:a", "flac");
      case "m4a", "aac" -> List.of("-vn", "-c:a", "aac", "-b:a", "192k");
      case "mp4", "mov", "mkv" -> List.of("-map", "0:v:0", "-map", "0:a:0?", "-vf", EVEN_SIZE,
          "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
          "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart");
      case "webm" -> List.of("-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libvpx-vp9", "-b:v", "0",
          "-crf", "33", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
          "-c:a", "libopus", "-b:a", "128k");
      case "avi" -> List.of("-map", "0:v:0", "-map", "0:a:0?", "-c:v", "mpeg4", "-q:v", "4",
          "-c:a", "libmp3lame", "-q:a", "4");
      case "gif" -> List.of("-an", "-vf", "fps=12,scale='min(480,iw)':-2:flags=lanczos,"
          + "split[a][b];[a]palettegen[p];[b][p]paletteuse", "-loop", "0");
      case "webp" -> List.of("-an", "-vf", "fps=15,scale='min(512,iw)':-2:flags=lanczos",
          "-c:v", "libwebp", "-q:v", "70", "-loop", "0");
      default -> throw new IllegalArgumentException("ffmpeg не умеет " + target);
    };
  }
}
