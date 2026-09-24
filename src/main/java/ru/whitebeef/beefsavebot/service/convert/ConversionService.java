package ru.whitebeef.beefsavebot.service.convert;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.whitebeef.beefsavebot.dto.Screen;
import ru.whitebeef.beefsavebot.service.convert.ConversionSession.FormatGroup;
import ru.whitebeef.beefsavebot.service.convert.Formats.Category;
import ru.whitebeef.beefsavebot.service.download.YtDlpClient;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;
import ru.whitebeef.beefsavebot.util.Html;

/**
 * Сценарий конвертации: пользователь присылает файл или архив, выбирает форматы кнопками и
 * получает результат. Кнопки имеют вид {@code cv:<сессия>:<действие>}:
 * <ul>
 *   <li>{@code t:<i>} — сконвертировать одиночный файл в i-й формат;</li>
 *   <li>{@code f:<g>} — выбрать формат для g-й группы файлов архива;</li>
 *   <li>{@code s:<g>:<i>} — назначить группе g формат i (-1 — не менять);</li>
 *   <li>{@code o} — обзор архива, {@code go} — запустить, {@code x} — отмена.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversionService {

  public static final String CALLBACK_PREFIX = "cv:";
  private static final Duration SESSION_TTL = Duration.ofHours(1);
  private static final int BUTTONS_PER_ROW = 4;
  private static final int REPORT_ERRORS_LIMIT = 5;

  private final ConverterRegistry registry;
  private final ArchiveService archiveService;
  private final Map<String, ConversionSession> sessions = new ConcurrentHashMap<>();
  private final AtomicLong sessionCounter = new AtomicLong();
  private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

  @PostConstruct
  void startCleaner() {
    cleaner.scheduleAtFixedRate(this::removeExpired, 10, 10, TimeUnit.MINUTES);
  }

  @PreDestroy
  void stopCleaner() {
    cleaner.shutdownNow();
    sessions.values().forEach(this::close);
  }

  /**
   * Принимает скачанный файл и возвращает экран выбора формата.
   *
   * @param downloaded временный файл, он переносится в директорию сессии
   */
  public Screen start(long userId, Path downloaded, String fileName) throws IOException {
    String format = Formats.of(fileName);
    String id = Long.toString(sessionCounter.incrementAndGet(), 36);
    Path workDir = Files.createTempDirectory("convert_" + id + "_");
    ConversionSession session = new ConversionSession(id, userId, workDir, fileName);
    try {
      Path file = workDir.resolve(safeName(fileName, format));
      Files.move(downloaded, file);
      session.setFile(file);
      session.setFormat(format);

      if (Formats.isArchive(format)) {
        prepareArchive(session);
        if (session.getGroups().isEmpty()) {
          close(session);
          return new Screen("📦 В архиве <b>" + Html.escape(fileName)
              + "</b> нет файлов, которые я умею конвертировать.\n\n" + supportedFormatsText(),
              null);
        }
        sessions.put(id, session);
        return overview(session);
      }
      if (!registry.supports(format)) {
        close(session);
        return new Screen("🤷 Не умею конвертировать " + (format.isEmpty() ? "файлы без расширения"
            : "формат <b>" + Formats.label(format) + "</b>") + ".\n\n" + supportedFormatsText(),
            null);
      }
      sessions.put(id, session);
      return singleScreen(session);
    } catch (IOException | RuntimeException e) {
      close(session);
      throw e;
    }
  }

  private void prepareArchive(ConversionSession session) throws IOException {
    session.setArchive(true);
    Path extracted = Files.createDirectories(session.getWorkDir().resolve("in"));
    session.setExtractedDir(extracted);
    List<Path> files = archiveService.extract(session.getFile(), session.getFormat(), extracted);

    Map<String, List<Path>> byFormat = new LinkedHashMap<>();
    List<Path> unsupported = new ArrayList<>();
    for (Path file : files) {
      String format = Formats.of(file.getFileName().toString());
      if (registry.supports(format)) {
        byFormat.computeIfAbsent(format, key -> new ArrayList<>()).add(file);
      } else {
        unsupported.add(file);
      }
    }
    session.setGroups(byFormat.entrySet().stream()
        .sorted(Comparator.comparing((Map.Entry<String, List<Path>> entry) ->
            -entry.getValue().size()).thenComparing(Map.Entry::getKey))
        .map(entry -> new FormatGroup(entry.getKey(), entry.getValue(),
            registry.targets(entry.getKey())))
        .toList());
    session.setUnsupported(unsupported);
  }

  public Optional<ConversionSession> find(String id) {
    return Optional.ofNullable(sessions.get(id));
  }

  // ---------------------------------------------------------------- экраны

  public Screen singleScreen(ConversionSession session) throws IOException {
    String text = "🔄 <b>Конвертация</b>\n\n"
        + "Файл: <b>" + Html.escape(session.getFileName()) + "</b> ("
        + Formats.label(session.getFormat()) + ", " + formatSize(Files.size(session.getFile()))
        + ")\n\nВо что сконвертировать?";
    List<String> targets = registry.targets(session.getFormat());
    List<List<InlineKeyboardButton>> rows = targetRows(targets,
        i -> route(session, "t:" + i));
    rows.add(List.of(button("✖️ Отмена", route(session, "x"))));
    return new Screen(text, markup(rows));
  }

  public Screen overview(ConversionSession session) {
    int total = session.getGroups().stream().mapToInt(group -> group.getFiles().size()).sum()
        + session.getUnsupported().size();
    StringBuilder text = new StringBuilder("📦 <b>").append(Html.escape(session.getFileName()))
        .append("</b> — файлов: ").append(total).append("\n\n<b>Что во что конвертировать:</b>\n");
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    for (int g = 0; g < session.getGroups().size(); g++) {
      FormatGroup group = session.getGroups().get(g);
      String target = group.selectedTarget();
      text.append(Formats.category(group.getFormat()).getEmoji()).append(' ')
          .append(Formats.label(group.getFormat())).append(" × ").append(group.getFiles().size())
          .append(" → ").append(target == null ? "не менять" : "<b>" + Formats.label(target)
              + "</b>").append('\n');
      rows.add(List.of(button(Formats.label(group.getFormat()) + " × " + group.getFiles().size()
              + " → " + (target == null ? "не менять" : Formats.label(target)),
          route(session, "f:" + g))));
    }
    if (!session.getUnsupported().isEmpty()) {
      Map<String, Long> unsupported = session.getUnsupported().stream()
          .collect(Collectors.groupingBy(path -> {
            String format = Formats.of(path.getFileName().toString());
            return format.isEmpty() ? "без расширения" : Formats.label(format);
          }, LinkedHashMap::new, Collectors.counting()));
      text.append("\n⚪ Останутся как есть: ").append(unsupported.entrySet().stream()
          .map(entry -> Html.escape(entry.getKey()) + " × " + entry.getValue())
          .collect(Collectors.joining(", "))).append('\n');
    }
    text.append("\nНажмите на формат, чтобы выбрать, во что его превратить. Результат придёт "
        + "zip-архивом с той же структурой папок.");
    rows.add(List.of(button("✅ Конвертировать", route(session, "go")),
        button("✖️ Отмена", route(session, "x"))));
    return new Screen(fit(text.toString()), markup(rows));
  }

  public Screen groupScreen(ConversionSession session, int groupIndex) {
    FormatGroup group = session.getGroups().get(groupIndex);
    String text = "Во что конвертировать " + Formats.category(group.getFormat()).getEmoji()
        + " <b>" + Formats.label(group.getFormat()) + "</b> (файлов: " + group.getFiles().size()
        + ")?";
    List<List<InlineKeyboardButton>> rows = targetRows(group.getTargets(),
        i -> route(session, "s:" + groupIndex + ":" + i));
    rows.add(List.of(button((group.getSelected() < 0 ? "✅ " : "") + "Не менять",
            route(session, "s:" + groupIndex + ":-1")),
        button("⬅️ Назад", route(session, "o"))));
    // Отмечаем текущий выбор
    if (group.getSelected() >= 0) {
      String selected = Formats.label(group.selectedTarget());
      rows.forEach(row -> row.forEach(button -> {
        if (button.getText().equals(selected)) {
          button.setText("✅ " + selected);
        }
      }));
    }
    return new Screen(text, markup(rows));
  }

  public void select(ConversionSession session, int groupIndex, int targetIndex) {
    FormatGroup group = session.getGroups().get(groupIndex);
    group.setSelected(targetIndex < 0 || targetIndex >= group.getTargets().size() ? -1
        : targetIndex);
  }

  // ---------------------------------------------------------------- конвертация

  public ConversionResult convertSingle(ConversionSession session, int targetIndex)
      throws Exception {
    List<String> targets = registry.targets(session.getFormat());
    if (targetIndex < 0 || targetIndex >= targets.size()) {
      throw new UserFacingException("Такого формата нет, пришлите файл ещё раз");
    }
    String target = targets.get(targetIndex);
    Path jobDir = Files.createDirectories(session.getWorkDir().resolve("job"));
    Path result = registry.convert(session.getFile(), session.getFormat(), target, jobDir,
        Formats.baseName(session.getFile().getFileName().toString()));
    return new ConversionResult(result, session.getFileName() + ": "
        + Formats.label(session.getFormat()) + " → " + Formats.label(target));
  }

  public ConversionResult convertArchive(ConversionSession session) throws Exception {
    if (session.getGroups().stream().allMatch(group -> group.getSelected() < 0)) {
      throw new UserFacingException("Выберите формат хотя бы для одного типа файлов");
    }
    Path extracted = session.getExtractedDir();
    Path outDir = Files.createDirectories(session.getWorkDir().resolve("out"));
    Path jobsDir = Files.createDirectories(session.getWorkDir().resolve("jobs"));
    List<Path> results = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    int converted = 0;
    int jobNumber = 0;

    // Сначала файлы, которые не меняются: они сохраняют свои имена, а при совпадении имён
    // суффикс получает сконвертированный файл
    for (FormatGroup group : session.getGroups()) {
      if (group.selectedTarget() == null) {
        for (Path relative : group.getFiles()) {
          results.add(copyTo(extracted.resolve(relative), outDir, relative));
        }
      }
    }
    for (Path relative : session.getUnsupported()) {
      results.add(copyTo(extracted.resolve(relative), outDir, relative));
    }

    for (FormatGroup group : session.getGroups()) {
      String target = group.selectedTarget();
      if (target == null) {
        continue;
      }
      for (Path relative : group.getFiles()) {
        Path source = extracted.resolve(relative);
        try {
          Path jobDir = Files.createDirectories(jobsDir.resolve(String.valueOf(jobNumber++)));
          Path result = registry.convert(source, group.getFormat(), target, jobDir,
              Formats.baseName(relative.getFileName().toString()));
          Path resultRelative = relative.resolveSibling(result.getFileName().toString());
          results.add(copyTo(result, outDir, resultRelative));
          converted++;
        } catch (Exception e) {
          log.warn("Не удалось сконвертировать {} в {}: {}", relative, target, e.getMessage());
          errors.add(relative + ": " + shortMessage(e));
          results.add(copyTo(source, outDir, relative));
        }
      }
    }
    Path zip = session.getWorkDir().resolve(Formats.baseName(session.getFileName())
        .replaceAll("[\\\\/:*?\"<>|]", "_") + "_converted.zip");
    archiveService.zip(outDir, results, zip);

    StringBuilder description = new StringBuilder(session.getFileName())
        .append(": сконвертировано ").append(converted).append(" из ")
        .append(converted + errors.size()).append(" (")
        .append(session.getGroups().stream()
            .filter(group -> group.getSelected() >= 0)
            .map(group -> Formats.label(group.getFormat()) + " → "
                + Formats.label(group.selectedTarget()))
            .collect(Collectors.joining(", ")))
        .append(')');
    if (!errors.isEmpty()) {
      description.append("\nНе получилось (оставлены как есть):\n")
          .append(errors.stream().limit(REPORT_ERRORS_LIMIT).collect(Collectors.joining("\n")));
      if (errors.size() > REPORT_ERRORS_LIMIT) {
        description.append("\n…и ещё ").append(errors.size() - REPORT_ERRORS_LIMIT);
      }
    }
    return new ConversionResult(zip, description.toString());
  }

  /**
   * Копирует файл в {@code outDir/relative}, не затирая уже существующие (a.jpg → a_1.jpg).
   */
  private Path copyTo(Path source, Path outDir, Path relative) throws IOException {
    Path target = outDir.resolve(relative).normalize();
    Files.createDirectories(target.getParent());
    String name = target.getFileName().toString();
    String base = Formats.baseName(name);
    String extension = name.substring(base.length());
    for (int i = 1; Files.exists(target); i++) {
      target = target.resolveSibling(base + "_" + i + extension);
    }
    Files.copy(source, target);
    return target;
  }

  public void close(ConversionSession session) {
    sessions.remove(session.getId());
    YtDlpClient.deleteDirectory(session.getWorkDir());
  }

  private void removeExpired() {
    Instant border = Instant.now().minus(SESSION_TTL);
    sessions.values().stream()
        .filter(session -> session.getCreatedAt().isBefore(border) && !session.getBusy().get())
        .toList()
        .forEach(this::close);
  }

  // ---------------------------------------------------------------- утилиты

  public String supportedFormatsText() {
    Map<Category, List<String>> byCategory = registry.sources().stream()
        .collect(Collectors.groupingBy(Formats::category, () -> new java.util.TreeMap<>(),
            Collectors.toList()));
    StringBuilder text = new StringBuilder("<b>Что я умею конвертировать:</b>\n");
    byCategory.forEach((category, formats) -> text.append(category.getEmoji()).append(' ')
        .append(category.getTitle()).append(": ")
        .append(formats.stream().sorted().map(Formats::label).collect(Collectors.joining(", ")))
        .append('\n'));
    text.append(Category.ARCHIVE.getEmoji()).append(" Архивы с файлами: ")
        .append(Formats.ARCHIVES.stream().sorted().map(Formats::label)
            .collect(Collectors.joining(", ")));
    return text.toString();
  }

  private List<List<InlineKeyboardButton>> targetRows(List<String> targets,
      java.util.function.IntFunction<String> route) {
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    List<InlineKeyboardButton> row = new ArrayList<>();
    Category previous = null;
    for (int i = 0; i < targets.size(); i++) {
      Category category = Formats.category(targets.get(i));
      // Каждая категория форматов начинается с новой строки
      if (!row.isEmpty() && (row.size() == BUTTONS_PER_ROW || category != previous)) {
        rows.add(row);
        row = new ArrayList<>();
      }
      row.add(button(Formats.label(targets.get(i)), route.apply(i)));
      previous = category;
    }
    if (!row.isEmpty()) {
      rows.add(row);
    }
    return rows;
  }

  private static String route(ConversionSession session, String action) {
    return CALLBACK_PREFIX + session.getId() + ":" + action;
  }

  private static InlineKeyboardButton button(String text, String callbackData) {
    return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
  }

  private static InlineKeyboardMarkup markup(List<List<InlineKeyboardButton>> rows) {
    return InlineKeyboardMarkup.builder().keyboard(rows).build();
  }

  /**
   * Обрезает текст по границе строки, чтобы не разорвать HTML-теги.
   */
  private static String fit(String text) {
    if (text.length() <= 4000) {
      return text;
    }
    int cut = text.lastIndexOf('\n', 3990);
    return text.substring(0, Math.max(cut, 0)) + "\n…";
  }

  private static String safeName(String fileName, String format) {
    String base = YtDlpClient.sanitizeFileName(Formats.baseName(fileName), "file_"
        + UUID.randomUUID());
    return format.isEmpty() ? base : base + "." + format;
  }

  public static String formatSize(long bytes) {
    if (bytes < 1024 * 1024) {
      return Math.max(1, bytes / 1024) + " КБ";
    }
    return String.format("%.1f МБ", bytes / 1024.0 / 1024.0);
  }

  private static String shortMessage(Throwable e) {
    String message = e instanceof UserFacingException ? e.getMessage()
        : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    message = message.replace('\n', ' ');
    return message.length() > 150 ? message.substring(0, 149) + "…" : message;
  }
}
