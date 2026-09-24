package ru.whitebeef.beefsavebot.service.convert.converters;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Удобный построитель таблицы «из чего → во что» для {@code FileConverter#conversions()}.
 */
public final class ConversionMatrix {

  private final Map<String, Set<String>> conversions = new LinkedHashMap<>();

  /**
   * Каждый из {@code sources} можно превратить в каждый из {@code targets} (кроме самого себя).
   */
  public ConversionMatrix add(Collection<String> sources, Collection<String> targets) {
    for (String source : sources) {
      Set<String> set = conversions.computeIfAbsent(source, key -> new LinkedHashSet<>());
      targets.stream().filter(target -> !target.equals(source)).forEach(set::add);
    }
    return this;
  }

  public Map<String, Set<String>> build() {
    return conversions;
  }
}
