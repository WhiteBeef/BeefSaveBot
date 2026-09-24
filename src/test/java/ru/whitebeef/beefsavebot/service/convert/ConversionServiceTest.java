package ru.whitebeef.beefsavebot.service.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.whitebeef.beefsavebot.dto.Screen;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

class ConversionServiceTest {

  /**
   * Тестовый конвертер: txt → html (верхний регистр), html → md, csv → json (всегда падает).
   */
  private static final FileConverter FAKE = new FileConverter() {
    @Override
    public Map<String, Set<String>> conversions() {
      return Map.of("txt", Set.of("html"), "html", Set.of("md"), "csv", Set.of("json"));
    }

    @Override
    public Path convert(ConversionJob job) throws IOException {
      if ("csv".equals(job.sourceFormat())) {
        throw new UserFacingException("сломанный файл");
      }
      String content = Files.readString(job.input());
      return Files.writeString(job.defaultOutput(), content.toUpperCase(Locale.ROOT));
    }
  };

  @TempDir
  Path tempDir;
  private ConversionService service;
  private ConverterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ConverterRegistry(List.of(FAKE));
    service = new ConversionService(registry, new ArchiveService());
  }

  @AfterEach
  void tearDown() {
    service.stopCleaner();
  }

  @Test
  void registryBuildsTwoStepChains() throws Exception {
    assertEquals(List.of("html", "md"), registry.targets("txt"));
    Path input = Files.writeString(tempDir.resolve("a.txt"), "hello");
    // txt → md строится цепочкой через html
    Path result = registry.convert(input, "txt", "md",
        Files.createDirectory(tempDir.resolve("work")), "a");
    assertEquals("a.md", result.getFileName().toString());
    assertEquals("HELLO", Files.readString(result));
  }

  @Test
  void convertsSingleFile() throws Exception {
    Path upload = Files.writeString(tempDir.resolve("upload"), "hi");
    Screen screen = service.start(1, upload, "note.txt");
    assertTrue(screen.text().contains("note.txt"));
    ConversionSession session = onlySession(screen);

    ConversionResult result = service.convertSingle(session, registry.targets("txt").indexOf("md"));
    assertEquals("note.md", result.file().getFileName().toString());
    assertEquals("HI", Files.readString(result.file()));
    service.close(session);
    assertFalse(Files.exists(session.getWorkDir()));
  }

  @Test
  void unsupportedFileGetsExplanation() throws Exception {
    Path upload = Files.writeString(tempDir.resolve("upload"), "x");
    Screen screen = service.start(1, upload, "image.xyz");
    assertTrue(screen.text().contains("XYZ"));
    assertEquals(null, screen.keyboard());
  }

  @Test
  void convertsArchivePerFormat() throws Exception {
    Path zip = tempDir.resolve("upload.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      put(out, "docs/a.txt", "first");
      put(out, "docs/b.txt", "second");
      put(out, "docs/a.md", "existing");
      put(out, "table.csv", "x,y");
      put(out, "photo.xyz", "binary");
      put(out, "__MACOSX/docs/._a.txt", "junk");
      put(out, "../evil.txt", "zip slip");
    }
    Screen screen = service.start(7, zip, "Архив.zip");
    ConversionSession session = onlySession(screen);
    assertTrue(session.isArchive());
    // txt (2 файла) идёт первым, потом csv; md и xyz этот конвертер не читает
    assertEquals(List.of("txt", "csv"),
        session.getGroups().stream().map(ConversionSession.FormatGroup::getFormat).toList());
    assertTrue(screen.text().contains("MD × 1, XYZ × 1"), screen.text());

    assertThrows(UserFacingException.class, () -> service.convertArchive(session));

    // txt → md через цепочку; уже лежащий рядом a.md сохраняет своё имя
    service.select(session, 0, session.getGroups().get(0).getTargets().indexOf("md"));
    service.select(session, 1, 0);
    ConversionResult result = service.convertArchive(session);

    Map<String, String> entries = read(result.file());
    assertEquals(Map.of(
        "docs/a.md", "existing",
        "docs/a_1.md", "FIRST",
        "docs/b.md", "SECOND",
        "table.csv", "x,y",
        "photo.xyz", "binary"), entries);
    assertTrue(result.description().contains("сконвертировано 2 из 3"), result.description());
    assertTrue(result.description().contains("table.csv: сломанный файл"), result.description());
    service.close(session);
  }

  private ConversionSession onlySession(Screen screen) {
    String data = screen.keyboard().getKeyboard().getFirst().getFirst().getCallbackData();
    String id = data.substring(ConversionService.CALLBACK_PREFIX.length()).split(":")[0];
    return service.find(id).orElseThrow();
  }

  private static void put(ZipOutputStream out, String name, String content) throws IOException {
    out.putNextEntry(new ZipEntry(name));
    out.write(content.getBytes(StandardCharsets.UTF_8));
    out.closeEntry();
  }

  private static Map<String, String> read(Path zip) throws IOException {
    Map<String, String> entries = new HashMap<>();
    try (ZipFile file = new ZipFile(zip.toFile())) {
      file.stream().forEach(entry -> {
        try {
          entries.put(entry.getName(), new String(file.getInputStream(entry).readAllBytes(),
              StandardCharsets.UTF_8));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
    }
    return entries;
  }
}
