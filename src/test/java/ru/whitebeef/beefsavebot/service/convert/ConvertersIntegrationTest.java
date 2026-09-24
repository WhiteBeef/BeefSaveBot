package ru.whitebeef.beefsavebot.service.convert;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.whitebeef.beefsavebot.service.convert.converters.DataFileConverter;
import ru.whitebeef.beefsavebot.service.convert.converters.FfmpegFileConverter;
import ru.whitebeef.beefsavebot.service.convert.converters.ImageMagickFileConverter;
import ru.whitebeef.beefsavebot.service.convert.converters.MarkdownFileConverter;
import ru.whitebeef.beefsavebot.service.convert.converters.OfficeFileConverter;
import ru.whitebeef.beefsavebot.service.convert.converters.PdfFileConverter;
import ru.whitebeef.beefsavebot.service.convert.converters.SvgFileConverter;

/**
 * Прогоняет все доступные конвертации на настоящих файлах. Долгий (LibreOffice), поэтому
 * запускается только явно: {@code mvn test -Dconverters.it=true -Dtest=ConvertersIntegrationTest}.
 * Свои образцы файлов можно подложить через {@code -Dconverters.samples=/путь/к/папке}.
 */
class ConvertersIntegrationTest {

  @TempDir
  Path tempDir;

  @Test
  void everyConversionProducesFile() throws Exception {
    assumeTrue(Boolean.getBoolean("converters.it"), "включается флагом -Dconverters.it=true");
    CommandRunner runner = new CommandRunner();
    ArchiveService archiveService = new ArchiveService();
    ConverterRegistry registry = new ConverterRegistry(List.of(
        new FfmpegFileConverter(runner), new ImageMagickFileConverter(runner),
        new PdfFileConverter(runner, archiveService), new OfficeFileConverter(runner),
        new DataFileConverter(), new MarkdownFileConverter(), new SvgFileConverter(runner)));

    Map<String, Path> samples = createSamples(runner, registry);
    List<String> failures = new ArrayList<>();
    int total = 0;
    for (Map.Entry<String, Path> sample : samples.entrySet()) {
      String source = sample.getKey();
      for (String target : registry.targets(source)) {
        total++;
        Path workDir = Files.createTempDirectory(tempDir, source + "_" + target + "_");
        long started = System.currentTimeMillis();
        try {
          Path result = registry.convert(sample.getValue(), source, target, workDir, "Тест файл");
          String resultFormat = Formats.of(result.getFileName().toString());
          if (!resultFormat.equals(target) && !"zip".equals(resultFormat)) {
            failures.add(source + " → " + target + ": получился " + resultFormat);
          }
          System.out.printf("OK   %-10s → %-6s %6d байт %5d мс%n", source, target,
              Files.size(result), System.currentTimeMillis() - started);
        } catch (Exception e) {
          failures.add(source + " → " + target + ": " + e.getMessage());
          System.out.printf("FAIL %-10s → %-6s %s%n", source, target, e.getMessage());
        }
      }
    }
    System.out.println("Всего конвертаций: " + total + ", ошибок: " + failures.size());
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  private Map<String, Path> createSamples(CommandRunner runner, ConverterRegistry registry)
      throws Exception {
    Path dir = Files.createDirectories(tempDir.resolve("samples"));
    Map<String, Path> samples = new LinkedHashMap<>();
    Duration timeout = Duration.ofMinutes(1);

    Path png = dir.resolve("sample.png");
    runner.run(List.of("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i",
        "testsrc=s=320x240:d=1", "-frames:v", "1", png.toString()), timeout);
    samples.put("png", png);
    Path gif = dir.resolve("sample.gif");
    runner.run(List.of("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i",
        "testsrc=s=160x120:d=1:r=10", gif.toString()), timeout);
    samples.put("gif", gif);
    Path wav = dir.resolve("sample.wav");
    runner.run(List.of("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i",
        "sine=frequency=440:duration=2", wav.toString()), timeout);
    samples.put("wav", wav);
    Path mp4 = dir.resolve("sample.mp4");
    runner.run(List.of("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i",
        "testsrc=s=320x240:d=2", "-f", "lavfi", "-i", "sine=duration=2", "-c:v", "libx264",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", mp4.toString()), timeout);
    samples.put("mp4", mp4);

    samples.put("svg", write(dir, "sample.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" "
        + "width=\"100\" height=\"100\"><circle cx=\"50\" cy=\"50\" r=\"40\" fill=\"red\"/></svg>"));
    samples.put("txt", write(dir, "sample.txt", "Привет, мир!\nВторая строка.\n"));
    samples.put("md", write(dir, "sample.md", """
        # Заголовок

        Текст с **жирным** и списком:

        - раз
        - два

        | a | b |
        |---|---|
        | 1 | 2 |
        """));
    samples.put("json", write(dir, "sample.json",
        "[{\"name\": \"Иван\", \"age\": 30, \"tags\": [\"a\", \"b\"]}, {\"name\": \"Анна\", \"age\": 25}]"));
    samples.put("yaml", write(dir, "sample.yaml", "server:\n  port: 8080\n  host: localhost\n"));
    samples.put("xml", write(dir, "sample.xml",
        "<root><user><name>Иван</name><age>30</age></user></root>"));
    samples.put("toml", write(dir, "sample.toml", "[server]\nport = 8080\nhost = \"localhost\"\n"));
    samples.put("properties", write(dir, "sample.properties", "server.port=8080\nserver.host=x\n"));
    samples.put("csv", write(dir, "sample.csv", "name,age\nИван,30\nАнна,25\n"));
    samples.put("html", write(dir, "sample.html",
        "<html><body><h1>Заголовок</h1><p>Абзац</p></body></html>"));

    // Офисные форматы делаем самим конвертером
    samples.put("docx", derive(registry, samples.get("txt"), "txt", "docx", dir));
    samples.put("pdf", derive(registry, samples.get("txt"), "txt", "pdf", dir));
    samples.put("xlsx", derive(registry, samples.get("csv"), "csv", "xlsx", dir));

    // Дополнительные образцы (например, pptx или heic): -Dconverters.samples=/путь/к/папке
    String extra = System.getProperty("converters.samples");
    if (extra != null && !extra.isBlank()) {
      try (var files = Files.list(Path.of(extra))) {
        files.filter(Files::isRegularFile).forEach(file ->
            samples.put(Formats.of(file.getFileName().toString()), file));
      }
    }
    return samples;
  }

  private Path derive(ConverterRegistry registry, Path input, String source, String target,
      Path dir) throws Exception {
    Path workDir = Files.createTempDirectory(dir, "derive_");
    return registry.convert(input, source, target, workDir, "derived_" + target);
  }

  private static Path write(Path dir, String name, String content) throws Exception {
    return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
  }
}
