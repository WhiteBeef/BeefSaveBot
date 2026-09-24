package ru.whitebeef.beefsavebot.service.convert;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Справочник форматов: нормализация расширений, категории, архивы.
 */
public final class Formats {

  @Getter
  @RequiredArgsConstructor
  public enum Category {
    IMAGE("🖼", "Изображения"),
    VIDEO("🎬", "Видео"),
    AUDIO("🎵", "Аудио"),
    DOCUMENT("📄", "Документы"),
    SPREADSHEET("📊", "Таблицы"),
    PRESENTATION("📽", "Презентации"),
    DATA("🧾", "Данные"),
    ARCHIVE("📦", "Архивы"),
    OTHER("📁", "Другое");

    private final String emoji;
    private final String title;
  }

  public static final Set<String> ARCHIVES = Set.of("zip", "7z", "tar", "tar.gz", "tar.bz2",
      "tar.xz");

  private static final Map<String, String> ALIASES = Map.ofEntries(
      Map.entry("jpeg", "jpg"), Map.entry("jpe", "jpg"), Map.entry("jfif", "jpg"),
      Map.entry("tif", "tiff"), Map.entry("heif", "heic"),
      Map.entry("htm", "html"), Map.entry("xhtml", "html"),
      Map.entry("yml", "yaml"), Map.entry("markdown", "md"),
      Map.entry("mpeg", "mpg"), Map.entry("oga", "ogg"),
      Map.entry("tgz", "tar.gz"), Map.entry("tbz2", "tar.bz2"), Map.entry("tbz", "tar.bz2"),
      Map.entry("txz", "tar.xz"));

  private static final Map<String, Category> CATEGORIES = new HashMap<>();

  static {
    register(Category.IMAGE, "png", "jpg", "webp", "bmp", "gif", "tiff", "ico", "heic", "avif",
        "svg", "psd", "tga", "ppm", "pgm", "pbm", "jp2", "jxl", "eps");
    register(Category.VIDEO, "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "mpg", "ts",
        "3gp", "m4v");
    register(Category.AUDIO, "mp3", "wav", "ogg", "opus", "flac", "m4a", "aac", "wma", "aiff",
        "amr", "ac3");
    register(Category.DOCUMENT, "pdf", "docx", "doc", "odt", "rtf", "txt", "html", "md", "epub");
    register(Category.SPREADSHEET, "xlsx", "xls", "ods", "csv");
    register(Category.PRESENTATION, "pptx", "ppt", "odp");
    register(Category.DATA, "json", "yaml", "xml", "toml", "properties");
    register(Category.ARCHIVE, ARCHIVES.toArray(String[]::new));
  }

  private Formats() {
  }

  private static void register(Category category, String... formats) {
    for (String format : formats) {
      CATEGORIES.put(format, category);
    }
  }

  public static String normalize(String extension) {
    if (extension == null) {
      return "";
    }
    String lower = extension.toLowerCase(Locale.ROOT).trim();
    return ALIASES.getOrDefault(lower, lower);
  }

  /**
   * Формат по имени файла (учитывает двойные расширения вроде {@code .tar.gz}).
   */
  public static String of(String fileName) {
    if (fileName == null) {
      return "";
    }
    String lower = fileName.toLowerCase(Locale.ROOT);
    for (String doubled : List.of("tar.gz", "tar.bz2", "tar.xz")) {
      if (lower.endsWith("." + doubled)) {
        return doubled;
      }
    }
    int dot = lower.lastIndexOf('.');
    return dot < 0 || dot == lower.length() - 1 ? "" : normalize(lower.substring(dot + 1));
  }

  /**
   * Имя файла без расширения формата.
   */
  public static String baseName(String fileName) {
    String format = of(fileName);
    if (format.isEmpty()) {
      return fileName;
    }
    String lower = fileName.toLowerCase(Locale.ROOT);
    for (String doubled : List.of("tar.gz", "tar.bz2", "tar.xz")) {
      if (lower.endsWith("." + doubled)) {
        return fileName.substring(0, fileName.length() - doubled.length() - 1);
      }
    }
    return fileName.substring(0, fileName.lastIndexOf('.'));
  }

  public static Category category(String format) {
    return CATEGORIES.getOrDefault(format, Category.OTHER);
  }

  public static boolean isArchive(String format) {
    return ARCHIVES.contains(format);
  }

  public static String label(String format) {
    return format.toUpperCase(Locale.ROOT);
  }

  /**
   * Расширение по MIME-типу для файлов без имени (фото, голосовые и т.п.).
   */
  public static String fromMimeType(String mimeType) {
    if (mimeType == null) {
      return "";
    }
    return switch (mimeType.toLowerCase(Locale.ROOT)) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      case "image/webp" -> "webp";
      case "image/gif" -> "gif";
      case "image/heic", "image/heif" -> "heic";
      case "video/mp4" -> "mp4";
      case "video/quicktime" -> "mov";
      case "video/webm" -> "webm";
      case "audio/mpeg" -> "mp3";
      case "audio/ogg" -> "ogg";
      case "audio/x-wav", "audio/wav" -> "wav";
      case "audio/mp4", "audio/x-m4a" -> "m4a";
      case "audio/flac" -> "flac";
      case "application/pdf" -> "pdf";
      case "application/zip" -> "zip";
      default -> "";
    };
  }
}
