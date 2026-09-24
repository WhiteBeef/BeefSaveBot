package ru.whitebeef.beefsavebot.service.convert.converters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.convert.ConversionJob;
import ru.whitebeef.beefsavebot.service.convert.FileConverter;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Структурированные данные: JSON, YAML, XML, TOML, CSV, .properties — друг в друга.
 */
@Order(50)
@Component
public class DataFileConverter implements FileConverter {

  private static final List<String> FORMATS = List.of("json", "yaml", "xml", "toml", "csv",
      "properties");

  private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private final YAMLMapper yaml = new YAMLMapper();
  private final XmlMapper xml = new XmlMapper();
  private final TomlMapper toml = new TomlMapper();
  private final CsvMapper csv = new CsvMapper();
  private final JavaPropsMapper properties = new JavaPropsMapper();

  @Override
  public Map<String, Set<String>> conversions() {
    return new ConversionMatrix().add(FORMATS, FORMATS).build();
  }

  @Override
  public Path convert(ConversionJob job) throws Exception {
    JsonNode tree;
    try {
      tree = read(job.input(), job.sourceFormat());
    } catch (Exception e) {
      throw new UserFacingException("Не удалось прочитать " + job.sourceFormat().toUpperCase()
          + ": " + e.getMessage());
    }
    Path output = job.defaultOutput();
    Files.writeString(output, write(tree, job.targetFormat()), StandardCharsets.UTF_8);
    return output;
  }

  private JsonNode read(Path input, String format) throws Exception {
    return switch (format) {
      case "json" -> json.readTree(input.toFile());
      case "yaml" -> yaml.readTree(input.toFile());
      case "xml" -> xml.readTree(input.toFile());
      case "toml" -> toml.readTree(input.toFile());
      case "properties" -> properties.readTree(input.toFile());
      case "csv" -> {
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        try (MappingIterator<Map<String, String>> iterator = csv
            .readerForMapOf(String.class)
            .with(CsvSchema.emptySchema().withHeader())
            .readValues(input.toFile())) {
          while (iterator.hasNext()) {
            rows.add(json.valueToTree(iterator.next()));
          }
        }
        yield rows;
      }
      default -> throw new IllegalArgumentException(format);
    };
  }

  private String write(JsonNode tree, String format) throws Exception {
    return switch (format) {
      case "json" -> json.writeValueAsString(tree);
      case "yaml" -> yaml.writeValueAsString(tree);
      case "xml" -> xml.writer().withRootName("root").withDefaultPrettyPrinter()
          .writeValueAsString(tree.isObject() ? tree : wrap(tree, "item"));
      case "toml" -> toml.writeValueAsString(tree.isObject() ? tree : wrap(tree, "items"));
      case "properties" -> properties.writeValueAsString(tree.isObject() ? tree
          : wrap(tree, "items"));
      case "csv" -> writeCsv(tree);
      default -> throw new IllegalArgumentException(format);
    };
  }

  private ObjectNode wrap(JsonNode tree, String name) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.set(name, tree);
    return root;
  }

  /**
   * CSV — это таблица, поэтому нужен список объектов. Если в корне объект с единственным
   * списком внутри, берём его.
   */
  private String writeCsv(JsonNode tree) throws Exception {
    JsonNode rows = tree;
    if (rows.isObject() && rows.size() == 1 && rows.elements().next().isArray()) {
      rows = rows.elements().next();
    }
    if (rows.isObject()) {
      rows = JsonNodeFactory.instance.arrayNode().add(rows);
    }
    if (!rows.isArray()) {
      throw new UserFacingException("В CSV можно превратить только список объектов");
    }
    Set<String> columns = new LinkedHashSet<>();
    for (JsonNode row : rows) {
      if (!row.isObject()) {
        throw new UserFacingException("В CSV можно превратить только список объектов");
      }
      row.fieldNames().forEachRemaining(columns::add);
    }
    CsvSchema.Builder schema = CsvSchema.builder().setUseHeader(true);
    columns.forEach(schema::addColumn);
    ArrayNode flat = JsonNodeFactory.instance.arrayNode();
    for (JsonNode row : rows) {
      ObjectNode flatRow = flat.addObject();
      for (Iterator<Map.Entry<String, JsonNode>> it = row.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> field = it.next();
        JsonNode value = field.getValue();
        // Вложенные структуры кладём в ячейку как JSON
        flatRow.put(field.getKey(), value.isContainerNode() ? json.writer()
            .without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(value)
            : value.isNull() ? "" : value.asText());
      }
    }
    return csv.writer(schema.build()).writeValueAsString(flat);
  }
}
