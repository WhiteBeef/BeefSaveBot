package ru.whitebeef.beefsavebot.service.convert.converters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.convert.CommandRunner;
import ru.whitebeef.beefsavebot.service.convert.ConversionJob;
import ru.whitebeef.beefsavebot.service.convert.FileConverter;
import ru.whitebeef.beefsavebot.service.download.YtDlpClient;

/**
 * Документы, таблицы и презентации через LibreOffice в headless-режиме.
 */
@Order(40)
@Component
@RequiredArgsConstructor
public class OfficeFileConverter implements FileConverter {

  private static final List<String> TEXT_IN = List.of("docx", "doc", "odt", "rtf", "txt", "html");
  private static final List<String> TEXT_OUT = List.of("pdf", "docx", "doc", "odt", "rtf", "txt",
      "html", "epub");
  private static final List<String> SHEET_IN = List.of("xlsx", "xls", "ods", "csv");
  private static final List<String> SHEET_OUT = List.of("pdf", "xlsx", "xls", "ods", "csv",
      "html");
  private static final List<String> SLIDES_IN = List.of("pptx", "ppt", "odp");
  private static final List<String> SLIDES_OUT = List.of("pdf", "pptx", "ppt", "odp");

  /**
   * Явные фильтры экспорта там, где формат по умолчанию не подходит.
   */
  private static final Map<String, String> TEXT_FILTERS = Map.of(
      "txt", "txt:Text (encoded):UTF8",
      "html", "html:XHTML Writer File:UTF8",
      "doc", "doc:MS Word 97",
      "docx", "docx:MS Word 2007 XML");
  private static final Map<String, String> SHEET_FILTERS = Map.of(
      "csv", "csv:Text - txt - csv (StarCalc):44,34,76,1",
      "html", "html:HTML (StarCalc)",
      "xls", "xls:MS Excel 97",
      "xlsx", "xlsx:Calc MS Excel 2007 XML");
  private static final Map<String, String> SLIDES_FILTERS = Map.of(
      "ppt", "ppt:MS PowerPoint 97",
      "pptx", "pptx:Impress MS PowerPoint 2007 XML");
  private static final Map<String, String> IMPORT_FILTERS = Map.of(
      "csv", "CSV:44,34,76,1",
      "txt", "Text (encoded):UTF8",
      "html", "HTML (StarWriter)");

  private final CommandRunner commandRunner;

  @Override
  public Map<String, Set<String>> conversions() {
    return new ConversionMatrix()
        .add(TEXT_IN, TEXT_OUT)
        .add(SHEET_IN, SHEET_OUT)
        .add(SLIDES_IN, SLIDES_OUT)
        .build();
  }

  @Override
  public boolean isAvailable() {
    return commandRunner.exists(binary());
  }

  private String binary() {
    return commandRunner.exists("soffice") ? "soffice" : "libreoffice";
  }

  @Override
  public Path convert(ConversionJob job) throws Exception {
    // У каждого запуска свой профиль, иначе параллельные конвертации мешают друг другу
    Path profile = Files.createTempDirectory("lo_profile_");
    Path outDir = Files.createDirectories(job.outputDir().resolve("office"));
    // LibreOffice называет результат по имени входного файла
    Path input = outDir.resolveSibling(job.baseName() + "." + job.sourceFormat());
    Files.copy(job.input(), input);
    try {
      List<String> command = new ArrayList<>(List.of(binary(), "--headless", "--norestore",
          "--nolockcheck", "-env:UserInstallation=" + profile.toUri()));
      String importFilter = IMPORT_FILTERS.get(job.sourceFormat());
      if (importFilter != null) {
        command.add("--infilter=" + importFilter);
      }
      command.addAll(List.of("--convert-to", exportFilter(job), "--outdir", outDir.toString(),
          input.toString()));
      commandRunner.run(command, Duration.ofMinutes(3));
      try (Stream<Path> files = Files.list(outDir)) {
        Path result = files.filter(Files::isRegularFile).findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "LibreOffice не смог сконвертировать файл в " + job.targetFormat()));
        Path output = job.defaultOutput();
        Files.move(result, output);
        return output;
      }
    } finally {
      YtDlpClient.deleteDirectory(profile);
      Files.deleteIfExists(input);
    }
  }

  private String exportFilter(ConversionJob job) {
    String source = job.sourceFormat();
    Map<String, String> filters = SHEET_IN.contains(source) ? SHEET_FILTERS
        : SLIDES_IN.contains(source) ? SLIDES_FILTERS : TEXT_FILTERS;
    return filters.getOrDefault(job.targetFormat(), job.targetFormat());
  }
}
