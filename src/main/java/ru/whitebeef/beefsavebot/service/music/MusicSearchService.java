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
    return new SearchResults(searchAll(query, others, RESULTS_PER_PROVIDER), false, others);
  }

  /**
   * Ищет у всех доступных поставщиков, выбранный — первым в списке.
   */
  public List<TrackResult> searchEverywhere(String query, MusicProvider preferred) {
    List<MusicProvider> order = new ArrayList<>(availableProviders());
    if (order.remove(preferred)) {
      order.addFirst(preferred);
    }
    return searchAll(query, order, RESULTS_PER_PROVIDER);
  }

  private List<TrackResult> searchAll(String query, List<MusicProvider> order, int limit) {
    List<CompletableFuture<List<TrackResult>>> futures = order.stream()
        .map(provider -> CompletableFuture.supplyAsync(
            () -> providers.get(provider).search(query, limit).stream().limit(limit).toList(),
            executor))
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

  public File download(TrackResult track, Quality quality) {
    MusicSearchProvider provider = providers.get(track.provider());
    if (provider == null) {
      throw new UserFacingException("Поставщик " + track.provider().getTitle() + " недоступен");
    }
    return provider.download(track, quality);
  }

  /**
   * Тот же трек у других поставщиков: по лучшему совпадению от каждого, чтобы предложить
   * пользователю выбор, если скачать из {@code failed} не получилось.
   */
  public List<TrackResult> findAlternatives(String query, MusicProvider failed) {
    List<MusicProvider> others = availableProviders().stream()
        .filter(provider -> provider != failed)
        .toList();
    return searchAll(query, others, 1);
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
