package ru.whitebeef.beefsavebot.service.convert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.convert.Formats.Category;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Собирает все {@link FileConverter} и строит маршруты «формат → формат». Если прямого
 * конвертера нет, маршрут строится в два шага через промежуточный формат (например,
 * md → html → docx или docx → pdf → png).
 */
@Slf4j
@Component
public class ConverterRegistry {

  /**
   * Форматы, через которые разрешено строить цепочки: они хорошо сохраняют содержимое.
   */
  private static final List<String> INTERMEDIATE_FORMATS = List.of("html", "csv", "pdf", "png");
  private static final Set<Category> TEXT_CATEGORIES = Set.of(Category.DOCUMENT,
      Category.SPREADSHEET, Category.PRESENTATION, Category.DATA);

  private final Map<String, Map<String, Route>> routes = new TreeMap<>();

  public ConverterRegistry(List<FileConverter> converters) {
    List<FileConverter> available = converters.stream().filter(FileConverter::isAvailable).toList();
    converters.stream().filter(converter -> !available.contains(converter)).forEach(converter ->
        log.warn("Конвертер {} отключён: не найдены нужные утилиты",
            converter.getClass().getSimpleName()));

    for (FileConverter converter : available) {
      converter.conversions().forEach((source, targets) -> targets.stream()
          .filter(target -> !target.equals(source))
          .forEach(target -> routes.computeIfAbsent(source, key -> new LinkedHashMap<>())
              .putIfAbsent(target, new Route(List.of(new Step(converter, source, target))))));
    }
    addChains();
    log.info("Доступно конвертаций: {} (из {} форматов)",
        routes.values().stream().mapToInt(Map::size).sum(), routes.size());
  }

  private void addChains() {
    Map<String, Map<String, Route>> chains = new TreeMap<>();
    routes.forEach((source, direct) -> {
      for (String intermediate : INTERMEDIATE_FORMATS) {
        Route first = direct.get(intermediate);
        Map<String, Route> next = routes.get(intermediate);
        if (first == null || next == null) {
          continue;
        }
        next.forEach((target, second) -> {
          if (target.equals(source) || direct.containsKey(target) || !chainMakesSense(source,
              intermediate, target)) {
            return;
          }
          List<Step> steps = new ArrayList<>(first.steps());
          steps.addAll(second.steps());
          chains.computeIfAbsent(source, key -> new LinkedHashMap<>())
              .putIfAbsent(target, new Route(steps));
        });
      }
    });
    chains.forEach((source, targets) -> targets.forEach((target, route) ->
        routes.get(source).putIfAbsent(target, route)));
  }

  /**
   * Отсекает бессмысленные цепочки: например, картинка → pdf → txt дала бы пустой текст.
   */
  private boolean chainMakesSense(String source, String intermediate, String target) {
    Category sourceCategory = Formats.category(source);
    Category targetCategory = Formats.category(target);
    if (!TEXT_CATEGORIES.contains(sourceCategory) && TEXT_CATEGORIES.contains(targetCategory)) {
      return false;
    }
    // Из PDF в картинки получается архив страниц, дальше его не сконвертировать
    return !("pdf".equals(intermediate) && Formats.category(target) == Category.IMAGE
        && !Set.of("png", "jpg", "tiff").contains(target));
  }

  public boolean supports(String source) {
    return routes.containsKey(source);
  }

  /**
   * Форматы, в которые можно превратить {@code source}, сгруппированные по категориям.
   */
  public List<String> targets(String source) {
    Map<String, Route> targets = routes.getOrDefault(source, Map.of());
    return targets.keySet().stream()
        .sorted(Comparator.comparing((String format) -> Formats.category(format).ordinal())
            .thenComparing(Comparator.naturalOrder()))
        .toList();
  }

  public Set<String> sources() {
    return routes.keySet();
  }

  /**
   * Конвертирует файл, при необходимости через промежуточный формат.
   *
   * @param workDir пустая директория для промежуточных и итоговых файлов
   */
  public Path convert(Path input, String source, String target, Path workDir, String baseName)
      throws Exception {
    Route route = routes.getOrDefault(source, Map.of()).get(target);
    if (route == null) {
      throw new UserFacingException("Не умею конвертировать " + Formats.label(source) + " в "
          + Formats.label(target));
    }
    Path current = input;
    String currentFormat = source;
    for (int i = 0; i < route.steps().size(); i++) {
      Step step = route.steps().get(i);
      if (!currentFormat.equals(step.source())) {
        throw new UserFacingException("Промежуточный результат получился в формате "
            + Formats.label(currentFormat) + ", дальше конвертировать не получится");
      }
      Path stepDir = Files.createDirectories(workDir.resolve("step" + i));
      current = step.converter().convert(new ConversionJob(current, step.source(), step.target(),
          stepDir, baseName));
      if (current == null || !Files.isRegularFile(current) || Files.size(current) == 0) {
        throw new IllegalStateException(step.converter().getClass().getSimpleName()
            + " не создал файл " + step.target());
      }
      currentFormat = Formats.of(current.getFileName().toString());
    }
    return current;
  }

  private record Step(FileConverter converter, String source, String target) {

  }

  private record Route(List<Step> steps) {

  }
}
