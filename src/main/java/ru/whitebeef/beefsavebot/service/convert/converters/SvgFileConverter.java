package ru.whitebeef.beefsavebot.service.convert.converters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.convert.CommandRunner;
import ru.whitebeef.beefsavebot.service.convert.ConversionJob;
import ru.whitebeef.beefsavebot.service.convert.FileConverter;

/**
 * SVG через rsvg-convert. В остальные растровые форматы реестр доведёт цепочкой через PNG.
 */
@Order(15)
@Component
@RequiredArgsConstructor
public class SvgFileConverter implements FileConverter {

  private final CommandRunner commandRunner;

  @Override
  public Map<String, Set<String>> conversions() {
    return Map.of("svg", Set.of("png", "pdf"));
  }

  @Override
  public boolean isAvailable() {
    return commandRunner.exists("rsvg-convert");
  }

  @Override
  public Path convert(ConversionJob job) throws Exception {
    Path output = job.defaultOutput();
    commandRunner.run(List.of("rsvg-convert", "--format", job.targetFormat(), "--zoom", "2",
        "--output", output.toString(), job.input().toString()), Duration.ofMinutes(2));
    return output;
  }
}
