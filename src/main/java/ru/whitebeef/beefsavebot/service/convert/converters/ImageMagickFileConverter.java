package ru.whitebeef.beefsavebot.service.convert.converters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.convert.CommandRunner;
import ru.whitebeef.beefsavebot.service.convert.ConversionJob;
import ru.whitebeef.beefsavebot.service.convert.FileConverter;

/**
 * Изображения через ImageMagick. Список форматов берётся из {@code -list format}, поэтому
 * зависит от того, с какими библиотеками собран ImageMagick на сервере.
 */
@Slf4j
@Order(20)
@Component
@RequiredArgsConstructor
public class ImageMagickFileConverter implements FileConverter {

  private static final List<String> INPUTS = List.of("png", "jpg", "webp", "bmp", "gif", "tiff",
      "ico", "heic", "avif", "svg", "psd", "tga", "ppm", "pgm", "pbm", "jp2", "jxl");
  private static final List<String> OUTPUTS = List.of("png", "jpg", "webp", "bmp", "gif", "tiff",
      "ico", "pdf", "avif", "jp2", "jxl");
  /**
   * Форматы, которые умеют хранить несколько кадров/страниц.
   */
  private static final Set<String> MULTI_FRAME = Set.of("gif", "webp", "tiff", "pdf");
  private static final Map<String, String> MAGICK_NAMES = Map.of("jpg", "JPEG", "tiff", "TIFF",
      "svg", "SVG");
  private static final Pattern FORMAT_LINE = Pattern.compile(
      "^\\s*([A-Z0-9]+)\\*?\\s+(?:\\S+\\s+)?([r-])([w-])[+-]\\s");

  private final CommandRunner commandRunner;
  private Map<String, Set<String>> conversions;

  private String binary() {
    return commandRunner.exists("magick") ? "magick" : "convert";
  }

  @Override
  public boolean isAvailable() {
    return commandRunner.exists("magick") || commandRunner.exists("convert");
  }

  @Override
  public synchronized Map<String, Set<String>> conversions() {
    if (conversions == null) {
      Set<String> readable = new HashSet<>();
      Set<String> writable = new HashSet<>();
      try {
        String output = commandRunner.run(List.of(binary(), "-list", "format"),
            Duration.ofSeconds(30));
        for (String line : output.split("\n")) {
          Matcher matcher = FORMAT_LINE.matcher(line);
          if (matcher.find()) {
            String name = matcher.group(1).toUpperCase(Locale.ROOT);
            if ("r".equals(matcher.group(2))) {
              readable.add(name);
            }
            if ("w".equals(matcher.group(3))) {
              writable.add(name);
            }
          }
        }
      } catch (Exception e) {
        log.warn("Не удалось получить список форматов ImageMagick: {}", e.getMessage());
      }
      List<String> inputs = INPUTS.stream().filter(format -> readable.contains(magickName(format)))
          .toList();
      List<String> outputs = OUTPUTS.stream()
          .filter(format -> writable.contains(magickName(format))).toList();
      conversions = new ConversionMatrix().add(inputs, outputs).build();
    }
    return conversions;
  }

  private static String magickName(String format) {
    return MAGICK_NAMES.getOrDefault(format, format.toUpperCase(Locale.ROOT));
  }

  @Override
  public Path convert(ConversionJob job) throws Exception {
    Path output = job.defaultOutput();
    List<String> command = new ArrayList<>();
    command.add(binary());
    if ("svg".equals(job.sourceFormat())) {
      command.addAll(List.of("-density", "150", "-background", "none"));
    }
    // В форматы без анимации берём только первый кадр
    command.add(job.input() + (MULTI_FRAME.contains(job.targetFormat()) ? "" : "[0]"));
    if (MULTI_FRAME.contains(job.targetFormat())) {
      // Оптимизированные GIF хранят кадры разного размера — приводим их к полному размеру
      command.add("-coalesce");
    }
    command.add("-auto-orient");
    switch (job.targetFormat()) {
      case "jpg", "bmp" -> command.addAll(List.of("-background", "white", "-alpha", "remove",
          "-alpha", "off", "-quality", "92"));
      case "ico" -> command.addAll(List.of("-resize", "256x256>", "-define",
          "icon:auto-resize=256,128,64,48,32,16"));
      case "webp", "avif", "jxl" -> command.addAll(List.of("-quality", "85"));
      default -> {
      }
    }
    command.add(output.toString());
    commandRunner.run(command, Duration.ofMinutes(3));
    return output;
  }
}
