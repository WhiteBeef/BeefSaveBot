package ru.whitebeef.beefsavebot.service.convert;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * Загруженный пользователем файл (или архив), ожидающий выбора формата.
 */
@Getter
@Setter
@RequiredArgsConstructor
public class ConversionSession {

  private final String id;
  private final long userId;
  private final Path workDir;
  /**
   * Исходное имя файла, как его прислал пользователь.
   */
  private final String fileName;
  private final Instant createdAt = Instant.now();
  private final AtomicBoolean busy = new AtomicBoolean();

  /**
   * Одиночный файл.
   */
  private Path file;
  private String format;

  /**
   * Архив: распакованные файлы, сгруппированные по формату.
   */
  private boolean archive;
  private Path extractedDir;
  private List<FormatGroup> groups = List.of();
  private List<Path> unsupported = List.of();

  @Getter
  @Setter
  @RequiredArgsConstructor
  public static class FormatGroup {

    private final String format;
    /**
     * Пути относительно {@link ConversionSession#getExtractedDir()}.
     */
    private final List<Path> files;
    private final List<String> targets;
    /**
     * Индекс выбранного формата в {@link #targets}, -1 — оставить как есть.
     */
    private int selected = -1;

    public String selectedTarget() {
      return selected < 0 ? null : targets.get(selected);
    }
  }
}
