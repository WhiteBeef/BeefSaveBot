package ru.whitebeef.beefsavebot.service.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.download.SoundCloudDownloadService;

class MusicSearchServiceTest {

  private static MusicSearchProvider provider(MusicProvider id, boolean available,
      List<String> titles) {
    return new MusicSearchProvider() {
      @Override
      public MusicProvider provider() {
        return id;
      }

      @Override
      public boolean isAvailable() {
        return available;
      }

      @Override
      public List<TrackResult> search(String query, int limit) {
        return titles.stream().map(title -> new TrackResult(id, title, "Artist", title)).toList();
      }

      @Override
      public File download(TrackResult track, Quality quality) {
        return null;
      }
    };
  }

  @Test
  void preferredProviderResultsComeAlone() {
    MusicSearchService service = new MusicSearchService(List.of(
        provider(MusicProvider.YANDEX, true, List.of("y1", "y2")),
        provider(MusicProvider.SOUNDCLOUD, true, List.of("s1"))));

    MusicSearchService.SearchResults results = service.search("q", MusicProvider.SOUNDCLOUD);
    assertTrue(results.fromPreferred());
    assertEquals(List.of("s1"), results.tracks().stream().map(TrackResult::id).toList());
  }

  @Test
  void fallsBackToOtherProvidersWhenPreferredFindsNothing() {
    MusicSearchService service = new MusicSearchService(List.of(
        provider(MusicProvider.YANDEX, true, List.of()),
        provider(MusicProvider.SOUNDCLOUD, true, List.of("s1", "s2"))));

    MusicSearchService.SearchResults results = service.search("q", MusicProvider.YANDEX);
    assertFalse(results.fromPreferred());
    assertEquals(List.of("s1", "s2"), results.tracks().stream().map(TrackResult::id).toList());
    assertEquals(List.of(MusicProvider.SOUNDCLOUD), results.searched());
  }

  @Test
  void unavailableProvidersAreSkipped() {
    MusicSearchService service = new MusicSearchService(List.of(
        provider(MusicProvider.YANDEX, false, List.of("y1")),
        provider(MusicProvider.SOUNDCLOUD, true, List.of("s1"))));

    assertEquals(List.of(MusicProvider.SOUNDCLOUD), service.availableProviders());
    // Яндекс выбран, но без токена недоступен — ищем в SoundCloud
    MusicSearchService.SearchResults results = service.search("q", MusicProvider.YANDEX);
    assertEquals(List.of("s1"), results.tracks().stream().map(TrackResult::id).toList());
  }

  @Test
  void searchEverywherePutsPreferredFirst() {
    MusicSearchService service = new MusicSearchService(List.of(
        provider(MusicProvider.YANDEX, true, List.of("y1")),
        provider(MusicProvider.SOUNDCLOUD, true, List.of("s1"))));

    assertEquals(List.of("s1", "y1"), service.searchEverywhere("q", MusicProvider.SOUNDCLOUD)
        .stream().map(TrackResult::id).toList());
  }

  @Test
  void cachedKeysFitIntoCallbackData() {
    MusicSearchService service = new MusicSearchService(List.of());
    TrackResult track = new TrackResult(MusicProvider.SOUNDCLOUD,
        "https://soundcloud.com/very-long-artist-name/very-long-track-name-remix", "A", "T");
    String key = service.rememberTrack(track);
    assertTrue(("mus:" + key).length() <= 64);
    assertEquals(track, service.findTrack(key));
  }

  @Test
  void parsesSoundCloudSearchResults() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    List<TrackResult> results = SoundCloudSearchProvider.parse(List.of(
        mapper.readTree("""
            {"_type": "url", "url": "https://soundcloud.com/artist/track",
             "title": "Track", "uploader": "Artist"}"""),
        mapper.readTree("""
            {"_type": "url", "webpage_url": "https://soundcloud.com/x/y", "title": "Other"}"""),
        mapper.readTree("{\"_type\": \"url\", \"title\": \"no url\"}")));
    assertEquals(2, results.size());
    assertEquals("Artist - Track", results.get(0).display());
    assertEquals("https://soundcloud.com/x/y", results.get(1).id());
    assertEquals("Other", results.get(1).display());
  }

  @Test
  void recognizesSoundCloudLinks() {
    SoundCloudDownloadService service = new SoundCloudDownloadService(null);
    assertTrue(service.canDownloadVideo("https://soundcloud.com/artist/track"));
    assertTrue(service.canDownloadVideo("https://on.soundcloud.com/AbCdEf"));
    assertTrue(service.canDownloadVideo("https://m.soundcloud.com/artist/track?si=1"));
    assertFalse(service.canDownloadVideo("https://youtube.com/watch?v=x"));
  }
}
