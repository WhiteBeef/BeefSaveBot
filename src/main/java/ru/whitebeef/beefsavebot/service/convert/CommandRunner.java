package ru.whitebeef.beefsavebot.service.convert;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Запуск внешних утилит (ffmpeg, ImageMagick, LibreOffice…) с таймаутом.
 */
@Slf4j
@Component
public class CommandRunner {

  private static final int ERROR_OUTPUT_LIMIT = 1500;

  /**
   * Есть ли исполняемый файл в PATH.
   */
  public boolean exists(String command) {
    String path = System.getenv("PATH");
    if (path == null) {
      return false;
    }
    for (String dir : path.split(File.pathSeparator)) {
      if (Files.isExecutable(Path.of(dir, command))) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return вывод команды (stdout + stderr)
   */
  public String run(List<String> command, Duration timeout) throws IOException,
      InterruptedException {
    Path outputFile = Files.createTempFile("cmd_", ".log");
    try {
      log.debug("Запуск: {}", String.join(" ", command));
      Process process = new ProcessBuilder(command)
          .redirectErrorStream(true)
          .redirectOutput(outputFile.toFile())
          .start();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new UserFacingException("Конвертация заняла слишком много времени");
      }
      String output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        String tail = output.length() > ERROR_OUTPUT_LIMIT
            ? output.substring(output.length() - ERROR_OUTPUT_LIMIT) : output;
        throw new IOException(command.getFirst() + " завершился с кодом " + process.exitValue()
            + ": " + tail.trim());
      }
      return output;
    } finally {
      Files.deleteIfExists(outputFile);
    }
  }
}
