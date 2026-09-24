package ru.whitebeef.beefsavebot.service.convert;

import java.nio.file.Path;

/**
 * Задание на один шаг конвертации.
 *
 * @param input        входной файл
 * @param sourceFormat формат входного файла (нормализованный, см. {@link Formats})
 * @param targetFormat нужный формат
 * @param outputDir    пустая директория для результата
 * @param baseName     имя результата без расширения
 */
public record ConversionJob(Path input, String sourceFormat, String targetFormat, Path outputDir,
                            String baseName) {

  /**
   * Путь, куда конвертеру удобно положить результат: {@code outputDir/baseName.targetFormat}.
   */
  public Path defaultOutput() {
    return outputDir.resolve(baseName + "." + targetFormat);
  }
}
