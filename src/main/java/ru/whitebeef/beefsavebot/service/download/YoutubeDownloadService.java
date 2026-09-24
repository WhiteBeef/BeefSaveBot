package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;

@Service
@Slf4j
public class YoutubeDownloadService extends AbstractYtDlpDownloadService {

  /**
   * yt-dlp проставляет оригинальной дорожке language_preference = 10, а дорожке по умолчанию
   * (в другой стране это часто автоперевод) — 5.
   */
  private static final int ORIGINAL_LANGUAGE_PREFERENCE = 10;
  private static final int DEFAULT_LANGUAGE_PREFERENCE = 5;
  private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
          "^(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/|live/)|youtu\\.be/)([\\w-]{11})(?:[?&]\\S*)?$")
      .asMatchPredicate();

  public YoutubeDownloadService(DownloadConfiguration downloadConfiguration,
      YtDlpClient ytDlpClient) {
    super(downloadConfiguration, ytDlpClient);
  }

  @Override
  protected boolean isCompatibleVideoCodec(String codec) {
    return codec.startsWith("avc1"); // H.264
  }

  @Override
  protected boolean isCompatibleAudioCodec(String codec) {
    return codec.startsWith("mp4a");
  }

  @Override
  protected List<JsonNode> selectAudioTrack(List<JsonNode> audios) {
    List<JsonNode> original = audios.stream().filter(this::isOriginalAudio).toList();
    if (!original.isEmpty()) {
      log.info("Найдена оригинальная аудиодорожка: {}",
          original.getFirst().path("format_note").asText(""));
      return original;
    }
    int bestPreference = audios.stream()
        .mapToInt(audio -> audio.path("language_preference").asInt(-1))
        .max()
        .orElse(-1);
    return audios.stream()
        .filter(audio -> audio.path("language_preference").asInt(-1) == bestPreference)
        .toList();
  }

  /**
   * Muxed-форматы (обычно 360p) содержат дорожку по умолчанию, которой на сервере в другой стране
   * оказывается автоперевод. Для видео с несколькими дорожками берём только видео + оригинальный
   * звук.
   */
  @Override
  protected boolean allowMuxed(List<JsonNode> audios) {
    long languages = audios.stream()
        .map(audio -> audio.path("language").asText(""))
        .filter(language -> !language.isBlank())
        .distinct()
        .count();
    boolean multiTrack = languages > 1 || audios.stream()
        .anyMatch(audio -> audio.path("language_preference").asInt(-1) >= DEFAULT_LANGUAGE_PREFERENCE);
    return !multiTrack;
  }

  private boolean isOriginalAudio(JsonNode format) {
    return format.path("language_preference").asInt(-1) >= ORIGINAL_LANGUAGE_PREFERENCE
        || format.path("format_note").asText("").toLowerCase(Locale.ROOT).contains("original");
  }

  @Override
  public boolean canDownloadVideo(String url) {
    return PATTERN_PREDICATE.test(url);
  }

  @Override
  public List<String> getSupportedSites() {
    return List.of("Youtube video", "Youtube shorts");
  }
}
