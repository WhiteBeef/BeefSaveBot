package ru.whitebeef.beefsavebot.service.convert.converters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.convert.ConversionJob;
import ru.whitebeef.beefsavebot.service.convert.FileConverter;
import ru.whitebeef.beefsavebot.util.Html;

/**
 * Markdown в HTML. Дальше через HTML реестр сам доберётся до PDF, DOCX, ODT и других.
 */
@Order(60)
@Component
public class MarkdownFileConverter implements FileConverter {

  private final Parser parser = Parser.builder()
      .extensions(List.of(TablesExtension.create()))
      .build();
  private final HtmlRenderer renderer = HtmlRenderer.builder()
      .extensions(List.of(TablesExtension.create()))
      .build();

  @Override
  public Map<String, Set<String>> conversions() {
    return Map.of("md", Set.of("html"));
  }

  @Override
  public Path convert(ConversionJob job) throws Exception {
    String markdown = Files.readString(job.input(), StandardCharsets.UTF_8);
    String body = renderer.render(parser.parse(markdown));
    String html = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <title>%s</title>
        <style>
        body { font-family: sans-serif; max-width: 800px; margin: 2em auto; line-height: 1.5; }
        pre, code { background: #f4f4f4; }
        table { border-collapse: collapse; }
        th, td { border: 1px solid #ccc; padding: 4px 8px; }
        </style>
        </head>
        <body>
        %s
        </body>
        </html>
        """.formatted(Html.escape(job.baseName()), body);
    Path output = job.defaultOutput();
    Files.writeString(output, html, StandardCharsets.UTF_8);
    return output;
  }
}
