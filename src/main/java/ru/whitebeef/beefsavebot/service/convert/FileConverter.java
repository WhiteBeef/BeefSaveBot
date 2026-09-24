package ru.whitebeef.beefsavebot.service.convert;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Конвертер файлов. Чтобы добавить новый, достаточно объявить Spring-бин с этим интерфейсом —
 * {@link ConverterRegistry} подхватит его сам, в том числе для двухшаговых цепочек.
 * Порядок бинов ({@code @Order}) задаёт приоритет, если одно преобразование умеют несколько
 * конвертеров.
 */
public interface FileConverter {

  /**
   * Что умеет конвертер: исходный формат → форматы, в которые он может его превратить.
   * Форматы — нормализованные расширения без точки (см. {@link Formats#normalize}).
   */
  Map<String, Set<String>> conversions();

  /**
   * Можно ли пользоваться конвертером (например, установлена ли нужная утилита).
   */
  default boolean isAvailable() {
    return true;
  }

  /**
   * Выполняет конвертацию.
   *
   * @return путь к результату. Обычно {@link ConversionJob#defaultOutput()}, но конвертер может
   * вернуть и другой файл (например, zip со страницами многостраничного PDF)
   */
  Path convert(ConversionJob job) throws Exception;
}
