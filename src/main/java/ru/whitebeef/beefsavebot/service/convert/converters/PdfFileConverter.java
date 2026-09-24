package ru.whitebeef.beefsavebot.service.convert.converters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.convert.ArchiveService;
import ru.whitebeef.beefsavebot.service.convert.CommandRunner;
import ru.whitebeef.beefsavebot.service.convert.ConversionJob;
import ru.whitebeef.beefsavebot.service.convert.FileConverter;

/**
 * PDF в картинки и текст через poppler-utils. Многостраничный PDF превращается в zip со
 * страницами.
 */
@Order(30)
@Component
@RequiredArgsConstructor
public class PdfFileConverter implements FileConverter {

  private static final int MAX_PAGES = 100;
  private static final int DPI = 150;

  private final CommandRunner commandRunner;
  private final ArchiveService archiveService;

  @Override
  public Map<String, Set<String>> conversions() {
    return Map.of("pdf", Set.of("png", "jpg", "tiff", "txt"));
  }

  @Override
  public boolean isAvailable() {
    return commandRunner.exists("pdftoppm") && commandRunner.exists("pdftotext");
  }

  @Override
  public Path convert(ConversionJob job) throws Exception {
    if ("txt".equals(job.targetFormat())) {
      Path output = job.defaultOutput();
      commandRunner.run(List.of("pdftotext", "-layout", "-enc", "UTF-8", job.input().toString(),
          output.toString()), Duration.ofMinutes(3));
      return output;
    }
    Path pagesDir = Files.createDirectories(job.outputDir().resolve("pages"));
    String type = switch (job.targetFormat()) {
      case "jpg" -> "-jpeg";
      case "tiff" -> "-tiff";
      default -> "-png";
    };
    commandRunner.run(List.of("pdftoppm", type, "-r", String.valueOf(DPI), "-l",
        String.valueOf(MAX_PAGES), job.input().toString(),
        pagesDir.resolve(job.baseName()).toString()), Duration.ofMinutes(5));
    List<Path> pages;
    try (Stream<Path> files = Files.list(pagesDir)) {
      pages = files.sorted().toList();
    }
    if (pages.size() == 1) {
      Path output = job.defaultOutput();
      Files.move(pages.getFirst(), output);
      return output;
    }
    Path zip = job.outputDir().resolve(job.baseName() + ".zip");
    archiveService.zip(pagesDir, pages, zip);
    return zip;
  }
}
