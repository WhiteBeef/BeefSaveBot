package ru.whitebeef.beefsavebot.service.music;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.download.DrmProtectedException;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Поиск музыки по названию: сначала у выбранного поставщика, а если там пусто — у всех остальных
 * сразу. Найденные треки хранятся в памяти под короткими ключами для кнопок.
 */
@Slf4j
@Service
public class MusicSearchService {

  public static final int RESULTS_PER_PROVIDER = 3;
  private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration CACHE_TTL = Duration.ofHours(6);

  private final Map<MusicProvider, MusicSearchProvider> providers = new ConcurrentHashMap<>();
  private final Map<String, Cached<TrackResult>> tracks = new ConcurrentHashMap<>();
  private final Map<String, Cached<String>> queries = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newCachedThreadPool();

  private record Cached<T>(T value, Instant createdAt) {

  }

  /**
   * @param fromPreferred найдено у выбранного поставщика (иначе — у остальных)
   */
  public record SearchResults(List<TrackResult> tracks, boolean fromPreferred,
                              List<MusicProvider> searched) {

  }

  public MusicSearchService(List<MusicSearchProvider> searchProviders) {
    searchProviders.forEach(provider -> providers.put(provider.provider(), provider));
  }

  public List<MusicProvider> availableProviders() {
    return providers.values().stream()
        .filter(MusicSearchProvider::isAvailable)
        .map(MusicSearchProvider::provider)
        .sorted()
        .toList();
  }

  public SearchResults search(String query, MusicProvider preferred) {
    MusicSearchProvider first = providers.get(preferred);
    if (first != null && first.isAvailable()) {
      List<TrackResult> found = first.search(query, RESULTS_PER_PROVIDER);
      if (!found.isEmpty()) {
        return new SearchResults(found, true, List.of(preferred));
      }
    }
    List<MusicProvider> others = availableProviders().stream()
        .filter(provider -> provider != preferred)
        .toList();
    return new SearchResults(searchAll(query, others), false, others);
  }

  /**
   * Ищет у всех доступных поставщиков, выбранный — первым в списке.
   */
  public List<TrackResult> searchEverywhere(String query, MusicProvider preferred) {
    List<MusicProvider> order = new ArrayList<>(availableProviders());
    if (order.remove(preferred)) {
      order.addFirst(preferred);
    }
    return searchAll(query, order);
  }

  private List<TrackResult> searchAll(String query, List<MusicProvider> order) {
    List<CompletableFuture<List<TrackResult>>> futures = order.stream()
        .map(provider -> CompletableFuture.supplyAsync(
            () -> providers.get(provider).search(query, RESULTS_PER_PROVIDER), executor))
        .toList();
    List<TrackResult> results = new ArrayList<>();
    for (int i = 0; i < futures.size(); i++) {
      try {
        results.addAll(futures.get(i).get(SEARCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
      } catch (Exception e) {
        log.warn("Поиск '{}' у {} не удался: {}", query, order.get(i), e.getMessage());
      }
    }
    return results;
  }

  /**
   * Скачивает трек. Если он защищён DRM, ищет тот же трек у других поставщиков.
   */
  public File download(TrackResult track, Quality quality) {
    MusicSearchProvider provider = providers.get(track.provider());
    if (provider == null) {
      throw new UserFacingException("Поставщик " + track.provider().getTitle() + " недоступен");
    }
    try {
      return provider.download(track, quality);
    } catch (DrmProtectedException e) {
      File replacement = downloadFromOtherProviders(track.display(), track.provider(), quality);
      if (replacement == null) {
        throw e;
      }
      return replacement;
    }
  }

  /**
   * Ищет трек по названию у всех доступных поставщиков, кроме {@code exclude}, и скачивает
   * первый найденный. {@code null}, если нигде не нашлось или не скачалось.
   */
  public File downloadFromOtherProviders(String query, MusicProvider exclude, Quality quality) {
    for (MusicProvider other : availableProviders()) {
      if (other == exclude) {
        continue;
      }
      MusicSearchProvider provider = providers.get(other);
      List<TrackResult> found = provider.search(query, 1);
      if (found.isEmpty()) {
        continue;
      }
      try {
        log.info("«{}» недоступен в {}, скачиваю из {}: {}", query, exclude, other,
            found.getFirst().display());
        return provider.download(found.getFirst(), quality);
      } catch (Exception e) {
        log.warn("Замена «{}» из {} не удалась: {}", query, other, e.getMessage());
      }
    }
    return null;
  }

  public String rememberTrack(TrackResult track) {
    return remember(tracks, track);
  }

  public TrackResult findTrack(String key) {
    Cached<TrackResult> cached = tracks.get(key);
    return cached == null ? null : cached.value();
  }

  public String rememberQuery(String query) {
    return remember(queries, query);
  }

  public String findQuery(String key) {
    Cached<String> cached = queries.get(key);
    return cached == null ? null : cached.value();
  }

  private <T> String remember(Map<String, Cached<T>> cache, T value) {
    Instant border = Instant.now().minus(CACHE_TTL);
    cache.values().removeIf(cached -> cached.createdAt().isBefore(border));
    String key = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    cache.put(key, new Cached<>(value, Instant.now()));
    return key;
  }
}
