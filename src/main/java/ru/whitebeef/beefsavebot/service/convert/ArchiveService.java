package ru.whitebeef.beefsavebot.service.convert;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Распаковка архивов (zip, 7z, tar, tar.gz, tar.bz2, tar.xz) и упаковка результата в zip.
 */
@Service
public class ArchiveService {

  public static final int MAX_ENTRIES = 300;
  public static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;

  /**
   * @return пути распакованных файлов относительно {@code targetDir}
   */
  public List<Path> extract(Path archive, String format, Path targetDir) throws IOException {
    Extractor extractor = new Extractor(targetDir);
    if ("7z".equals(format)) {
      try (SevenZFile sevenZ = SevenZFile.builder().setPath(archive).get()) {
        SevenZArchiveEntry entry;
        while ((entry = sevenZ.getNextEntry()) != null) {
          if (!entry.isDirectory()) {
            extractor.write(entry.getName(), sevenZ.getInputStream(entry));
          }
        }
      }
      return extractor.files;
    }
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
        ArchiveInputStream<?> in = open(raw, format)) {
      ArchiveEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (!entry.isDirectory() && in.canReadEntryData(entry)) {
          extractor.write(entry.getName(), in);
        }
      }
    }
    return extractor.files;
  }

  private ArchiveInputStream<?> open(InputStream raw, String format) throws IOException {
    return switch (format) {
      case "zip" -> new ZipArchiveInputStream(raw, "UTF-8", true, true);
      case "tar" -> new TarArchiveInputStream(raw);
      case "tar.gz" -> new TarArchiveInputStream(new GzipCompressorInputStream(raw));
      case "tar.bz2" -> new TarArchiveInputStream(new BZip2CompressorInputStream(raw));
      case "tar.xz" -> new TarArchiveInputStream(new XZCompressorInputStream(raw));
      default -> throw new UserFacingException("Архивы " + format + " не поддерживаются");
    };
  }

  /**
   * Упаковывает файлы в zip, сохраняя их пути относительно {@code baseDir}.
   */
  public void zip(Path baseDir, List<Path> files, Path zip) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (Path file : files) {
        Path absolute = file.isAbsolute() ? file : baseDir.resolve(file);
        out.putNextEntry(new ZipEntry(baseDir.relativize(absolute).toString().replace('\\', '/')));
        Files.copy(absolute, out);
        out.closeEntry();
      }
    }
  }

  private static final class Extractor {

    private final Path targetDir;
    private final List<Path> files = new ArrayList<>();
    private long totalBytes;

    private Extractor(Path targetDir) {
      this.targetDir = targetDir.toAbsolutePath().normalize();
    }

    private void write(String name, InputStream in) throws IOException {
      if (isJunk(name)) {
        return;
      }
      if (files.size() >= MAX_ENTRIES) {
        throw new UserFacingException("В архиве слишком много файлов (максимум " + MAX_ENTRIES
            + ")");
      }
      Path target = targetDir.resolve(name).normalize();
      // Защита от zip-slip: путь не должен выходить за пределы директории
      if (!target.startsWith(targetDir) || target.equals(targetDir)) {
        return;
      }
      Files.createDirectories(target.getParent());
      byte[] buffer = new byte[64 * 1024];
      try (OutputStream out = Files.newOutputStream(target)) {
        int read;
        while ((read = in.read(buffer)) != -1) {
          totalBytes += read;
          // Защита от zip-бомб: считаем реально распакованные байты
          if (totalBytes > MAX_TOTAL_BYTES) {
            throw new UserFacingException("Архив слишком большой после распаковки (максимум "
                + MAX_TOTAL_BYTES / 1024 / 1024 + " МБ)");
          }
          out.write(buffer, 0, read);
        }
      }
      files.add(targetDir.relativize(target));
    }

    private static boolean isJunk(String name) {
      String normalized = name.replace('\\', '/');
      String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
      return normalized.startsWith("__MACOSX/") || fileName.startsWith("._")
          || fileName.equals(".DS_Store") || fileName.equalsIgnoreCase("Thumbs.db")
          || fileName.isEmpty();
    }
  }
}
