package ru.whitebeef.beefsavebot.service.media;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.model.Quality;

/**
 * Склеивает картинки в вертикальное видео с плавными переходами под музыку.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlideshowBuilder {

  private static final double TRANSITION_SECONDS = 0.5;
  private static final int FPS = 30;
  public static final int MAX_SLIDES = 60;

  private final DownloadConfiguration downloadConfiguration;

  /**
   * @param audio музыка или {@code null}; если она короче слайдов — зацикливается
   */
  public File build(List<Path> images, Path audio, Quality quality, Path outputDir,
      String baseName) throws IOException, InterruptedException {
    if (images.isEmpty()) {
      throw new UserFacingException("В слайд-шоу нет картинок");
    }
    List<Path> slides = images.size() > MAX_SLIDES ? images.subList(0, MAX_SLIDES) : images;
    int width = switch (quality) {
      case LOW -> 540;
      case MEDIUM -> 720;
      case HIGH -> 1080;
    };
    int height = width * 16 / 9;
    double slideSeconds = Math.max(1, downloadConfiguration.getSlideSeconds());
    double transition = slides.size() > 1 ? Math.min(TRANSITION_SECONDS, slideSeconds / 2) : 0;
    double total = slides.size() * slideSeconds - (slides.size() - 1) * transition;

    List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel",
        "error", "-y"));
    for (Path slide : slides) {
      command.addAll(List.of("-loop", "1", "-framerate", String.valueOf(FPS),
          "-t", number(slideSeconds), "-i", slide.toString()));
    }
    if (audio != null) {
      command.addAll(List.of("-stream_loop", "-1", "-i", audio.toString()));
    }

    StringBuilder filter = new StringBuilder();
    for (int i = 0; i < slides.size(); i++) {
      // Вписываем картинку в кадр 9:16, поля заливаем размытым фоном из неё же
      filter.append("[").append(i).append(":v]scale=").append(width).append(':').append(height)
          .append(":force_original_aspect_ratio=increase,crop=").append(width).append(':')
          .append(height).append(",boxblur=20:5[bg").append(i).append("];")
          .append("[").append(i).append(":v]scale=").append(width).append(':').append(height)
          .append(":force_original_aspect_ratio=decrease[fg").append(i).append("];")
          .append("[bg").append(i).append("][fg").append(i)
          .append("]overlay=(W-w)/2:(H-h)/2,setsar=1,fps=").append(FPS)
          .append(",format=yuv420p[s").append(i).append("];");
    }
    String last = "s0";
    for (int i = 1; i < slides.size(); i++) {
      double offset = i * (slideSeconds - transition);
      String next = "x" + i;
      filter.append("[").append(last).append("][s").append(i)
          .append("]xfade=transition=fade:duration=").append(number(transition))
          .append(":offset=").append(number(offset)).append("[").append(next).append("];");
      last = next;
    }
    if (audio != null) {
      double fadeStart = Math.max(0, total - 1);
      filter.append("[").append(slides.size()).append(":a]atrim=0:").append(number(total))
          .append(",afade=t=out:st=").append(number(fadeStart)).append(":d=1[a];");
    }
    filter.setLength(filter.length() - 1);

    command.addAll(List.of("-filter_complex", filter.toString(), "-map", "[" + last + "]"));
    if (audio != null) {
      command.addAll(List.of("-map", "[a]", "-c:a", "aac", "-b:a", "160k"));
    }
    Path output = outputDir.resolve(baseName + ".mp4");
    command.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
        "-pix_fmt", "yuv420p", "-t", number(total), "-movflags", "+faststart",
        output.toString()));

    log.info("Склейка слайд-шоу: {} слайдов, {} с", slides.size(), number(total));
    Process process = new ProcessBuilder(command).inheritIO().start();
    if (!process.waitFor(downloadConfiguration.getFfmpegTimeoutMinutes(), TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new UserFacingException("Склейка слайд-шоу заняла слишком много времени");
    }
    if (process.exitValue() != 0 || !Files.exists(output)) {
      throw new IOException("ffmpeg не смог склеить слайд-шоу (код " + process.exitValue() + ")");
    }
    return output.toFile();
  }

  private static String number(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }
}
