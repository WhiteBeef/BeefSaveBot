package ru.whitebeef.beefsavebot.api;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;
import ru.whitebeef.beefsavebot.dto.DownloadOptions;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.service.TelegramBotService;
import ru.whitebeef.beefsavebot.service.convert.ConverterRegistry;
import ru.whitebeef.beefsavebot.service.convert.Formats;
import ru.whitebeef.beefsavebot.service.download.MediaType;
import ru.whitebeef.beefsavebot.service.download.VideoDownloadService;
import ru.whitebeef.beefsavebot.service.media.CropRange;
import ru.whitebeef.beefsavebot.service.media.MediaProcessingService;
import ru.whitebeef.beefsavebot.service.media.MediaSender;
import ru.whitebeef.beefsavebot.service.media.TimeCode;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;
import ru.whitebeef.beefsavebot.service.music.MusicSearchService;
import ru.whitebeef.beefsavebot.service.music.TrackResult;

/**
 * REST API бота — для интеграций (Turby): всё, что бот умеет в Telegram, без Telegram-экранов.
 * Видео и звук по ссылке (с обрезкой), поиск и скачивание музыки, конвертация файлов.
 *
 * <p>Результат — {@code deliver}: {@code telegram} — бот сам пришлёт файл (до 50 МБ, больше —
 * частями) в {@code chatId}, задачей в фоне; {@code inline} — файл в ответе base64, если он
 * не больше {@code api.inline-max-bytes} (по умолчанию 4 МБ), — для веба, где Telegram нет.
 * Бот пишет только тем, кто нажал у него «Старт».
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class BeefSaveApiController {

  private final VideoDownloadService videos;
  private final MediaProcessingService media;
  private final MediaSender sender;
  private final MusicSearchService music;
  private final ConverterRegistry converters;
  private final DownloadConfiguration download;
  private final TelegramBotService bot;
  private final ApiJobs jobs;
  private final long inlineMax;

  public BeefSaveApiController(VideoDownloadService videos, MediaProcessingService media, MediaSender sender,
      MusicSearchService music, ConverterRegistry converters, DownloadConfiguration download,
      @Lazy TelegramBotService bot, ApiJobs jobs,
      @Value("${api.inline-max-bytes:4194304}") long inlineMax) {
    this.videos = videos;
    this.media = media;
    this.sender = sender;
    this.music = music;
    this.converters = converters;
    this.download = download;
    this.bot = bot;
    this.jobs = jobs;
    this.inlineMax = inlineMax;
  }

  public record MediaRequest(String url, String format, String quality, String start, String end,
                             String deliver, String chatId) {
  }

  public record MusicRequest(String key, String query, String provider, String quality, String deliver,
                             String chatId) {
  }

  public record ConvertRequest(String fileName, String base64, String to, String deliver, String chatId) {
  }

  // ─────────────────────────────── что умеет ─────────────────────────────────

  @GetMapping("/info")
  public Map<String, Object> info() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("videoSites", videos.getSupportedSites());
    out.put("formats", Arrays.stream(OutputFormat.values())
        .map(f -> Map.of("name", f.name(), "description", f.getDescription())).toList());
    out.put("qualities", Arrays.stream(Quality.values()).map(Enum::name).toList());
    out.put("musicProviders", music.availableProviders().stream().map(Enum::name).toList());
    out.put("convertFrom", converters.sources().stream().sorted().toList());
    out.put("telegramMaxBytes", MediaSender.TELEGRAM_UPLOAD_LIMIT);
    out.put("inlineMaxBytes", inlineMax);
    return out;
  }

  // ─────────────────────────────── видео и звук ──────────────────────────────

  /** Видео или звук по ссылке; start/end — обрезка (таймкоды «1:23», «0:01:23:12»). */
  @PostMapping("/media")
  public Map<String, Object> media(@RequestBody MediaRequest r) {
    String url = require(r.url(), "url");
    if (!videos.canDownloadVideo(url)) {
      throw new UserFacingException("Не умею скачивать по этой ссылке. Умею: " + videos.getSupportedSites());
    }
    MediaType type = videos.getMediaType(url);
    OutputFormat format = r.format() != null && !r.format().isBlank() ? parseFormat(r.format())
        : type == MediaType.AUDIO ? OutputFormat.MP3 : OutputFormat.MP4;
    Quality quality = parseQuality(r.quality());
    CropRange crop = crop(r.start(), r.end());
    DownloadOptions options = DownloadOptions.of(quality, format, crop != null, download);
    String what = (format.isAudioOnly() ? "звук" : "видео") + " " + url + (crop == null ? "" : " " + crop);
    return deliver(r.deliver(), r.chatId(), what, () -> {
      File source = videos.downloadVideo(url, options);
      File result = media.process(source, format, quality, crop);
      if (!result.equals(source)) {
        cleanup(source);
      }
      return new Result(result.toPath(), format, null);
    });
  }

  // ─────────────────────────────── музыка ────────────────────────────────────

  /** Поиск треков; {@code key} результата — для {@code POST /music}. */
  @GetMapping("/music/search")
  public List<Map<String, String>> search(@RequestParam("q") String q,
      @RequestParam(value = "provider", required = false) String provider) {
    MusicProvider preferred = provider == null ? null : MusicProvider.parse(provider);
    return music.searchEverywhere(require(q, "q"), preferred).stream().map(this::track).toList();
  }

  /** Трек по {@code key} из поиска или первый найденный по {@code query} — MP3. */
  @PostMapping("/music")
  public Map<String, Object> track(@RequestBody MusicRequest r) {
    TrackResult track;
    if (r.key() != null && !r.key().isBlank()) {
      track = music.findTrack(r.key().strip());
      if (track == null) {
        throw new UserFacingException("Результат поиска устарел — поищите ещё раз");
      }
    } else {
      MusicProvider preferred = r.provider() == null ? null : MusicProvider.parse(r.provider());
      track = music.searchEverywhere(require(r.query(), "key или query"), preferred).stream().findFirst()
          .orElseThrow(() -> new UserFacingException("Ничего не нашёл по «" + r.query() + "»"));
    }
    Quality quality = parseQuality(r.quality());
    TrackResult t = track;
    return deliver(r.deliver(), r.chatId(), "трек " + t.display(), () -> {
      File source = music.download(t, quality);
      File result = media.process(source, OutputFormat.MP3, quality, null);
      if (!result.equals(source)) {
        cleanup(source);
      }
      return new Result(result.toPath(), OutputFormat.MP3, null);
    });
  }

  private Map<String, String> track(TrackResult t) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("key", music.rememberTrack(t));
    m.put("provider", t.provider().name());
    m.put("artist", t.artist() == null ? "" : t.artist());
    m.put("title", t.title());
    return m;
  }

  // ─────────────────────────────── конвертация ───────────────────────────────

  /** Во что можно превратить файл с таким именем (по расширению). */
  @GetMapping("/convert/targets")
  public Map<String, Object> targets(@RequestParam("fileName") String fileName) {
    String source = Formats.of(require(fileName, "fileName"));
    return Map.of("from", source == null ? "" : source,
        "to", source == null || !converters.supports(source) ? List.of() : converters.targets(source));
  }

  /** Файл (base64) — в формат {@code to}. */
  @PostMapping("/convert")
  public Map<String, Object> convert(@RequestBody ConvertRequest r) throws IOException {
    String name = require(r.fileName(), "fileName");
    String source = Formats.of(name);
    String target = Formats.normalize(require(r.to(), "to"));
    if (source == null || !converters.supports(source)) {
      throw new UserFacingException("Файлы «" + name + "» не конвертирую. Умею из: "
          + String.join(", ", converters.sources().stream().sorted().toList()));
    }
    if (!converters.targets(source).contains(target)) {
      throw new UserFacingException("Из " + source + " умею только в: " + String.join(", ", converters.targets(source)));
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(require(r.base64(), "base64"));
    } catch (IllegalArgumentException e) {
      throw new UserFacingException("base64 файла не разобрался");
    }
    Path dir = Files.createTempDirectory("beefsave-api-");
    Path input = dir.resolve("input." + source.replace('/', '_'));
    Files.write(input, bytes);
    return deliver(r.deliver(), r.chatId(), "конвертация " + name + " → " + target, () -> {
      Path out = converters.convert(input, source, target, dir, Formats.baseName(name));
      return new Result(out, null, Formats.baseName(name) + " → " + target);
    }, dir);
  }

  // ─────────────────────────────── задачи ────────────────────────────────────

  @GetMapping("/jobs/{id}")
  public ResponseEntity<ApiJobs.Job> job(@PathVariable String id) {
    ApiJobs.Job j = jobs.get(id);
    return j == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(j);
  }

  // ─────────────────────────────── доставка ──────────────────────────────────

  /** Готовый файл: путь, формат медиа (для отправки видео/аудио) или подпись документа. */
  private record Result(Path file, OutputFormat format, String caption) {
  }

  @FunctionalInterface
  private interface Producer {
    Result make() throws Exception;
  }

  private Map<String, Object> deliver(String deliver, String chatId, String what, Producer producer) {
    return deliver(deliver, chatId, what, producer, null);
  }

  private Map<String, Object> deliver(String deliver, String chatId, String what, Producer producer, Path tmpDir) {
    String mode = deliver == null || deliver.isBlank() ? "telegram" : deliver.strip().toLowerCase();
    if (mode.equals("inline")) {
      try {
        return inline(producer.make());
      } catch (UserFacingException | TooBig e) {
        throw e;
      } catch (Exception e) {
        throw new UserFacingException("Не вышло: " + e.getMessage());
      } finally {
        deleteDir(tmpDir);
      }
    }
    if (!mode.equals("telegram")) {
      throw new UserFacingException("deliver — telegram или inline");
    }
    String chat = require(chatId, "chatId (Telegram, куда прислать)");
    ApiJobs.Job job = jobs.submit(what, () -> {
      Result res = null;
      try {
        res = producer.make();
        long size = Files.size(res.file());
        if (res.format() != null) {
          sender.send(bot, chat, null, res.file().toFile(), res.format(), false);
        } else {
          if (size > MediaSender.TELEGRAM_UPLOAD_LIMIT) {
            throw new UserFacingException("Результат больше 50 МБ — Telegram не даст его отправить");
          }
          bot.execute(SendDocument.builder().chatId(chat).document(new InputFile(res.file().toFile()))
              .caption(res.caption()).build());
        }
        return "Отправил в Telegram: " + res.file().getFileName() + " (" + size / 1024 + " КБ)";
      } finally {
        if (res != null) {
          cleanup(res.file().toFile());
        }
        deleteDir(tmpDir);
      }
    });
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("jobId", job.id());
    out.put("status", job.status());
    out.put("message", "Принято: " + what + ". Бот пришлёт файл в Telegram; ход — GET /api/v1/jobs/" + job.id());
    return out;
  }

  private Map<String, Object> inline(Result res) throws IOException {
    try {
      long size = Files.size(res.file());
      if (size > inlineMax) {
        throw new TooBig(size);
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("fileName", res.file().getFileName().toString());
      out.put("mime", mime(res.file()));
      out.put("size", size);
      out.put("base64", Base64.getEncoder().encodeToString(Files.readAllBytes(res.file())));
      return out;
    } finally {
      cleanup(res.file().toFile());
    }
  }

  /** Файл больше предела ответа — пусть пришлют в Telegram. */
  static final class TooBig extends RuntimeException {
    final long size;

    TooBig(long size) {
      super("Файл " + size / 1024 / 1024 + " МБ — больше предела ответа; попросите прислать в Telegram");
      this.size = size;
    }
  }

  @ExceptionHandler(UserFacingException.class)
  ResponseEntity<Map<String, Object>> userFacing(UserFacingException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(TooBig.class)
  ResponseEntity<Map<String, Object>> tooBig(TooBig e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", e.getMessage(),
        "size", e.size, "inlineMaxBytes", inlineMax));
  }

  // ─────────────────────────────── разбор ────────────────────────────────────

  private static String require(String v, String what) {
    if (v == null || v.isBlank()) {
      throw new UserFacingException("Нужен параметр " + what);
    }
    return v.strip();
  }

  private static OutputFormat parseFormat(String v) {
    OutputFormat f = OutputFormat.parse(v.strip());
    if (f == null) {
      throw new UserFacingException("Формат — " + Arrays.toString(OutputFormat.values()));
    }
    return f;
  }

  private static Quality parseQuality(String v) {
    if (v == null || v.isBlank()) {
      return Quality.MEDIUM;
    }
    Quality q = Quality.parse(v.strip());
    if (q == null) {
      throw new UserFacingException("Качество — " + Arrays.toString(Quality.values()));
    }
    return q;
  }

  static CropRange crop(String start, String end) {
    boolean s = start != null && !start.isBlank();
    boolean e = end != null && !end.isBlank();
    if (!s && !e) {
      return null;
    }
    if (!s || !e) {
      throw new UserFacingException("Для обрезки нужны и start, и end");
    }
    CropRange range = new CropRange(TimeCode.parse(start), TimeCode.parse(end));
    if (range.start().frame() == null && range.end().frame() == null
        && range.end().seconds() <= range.start().seconds()) {
      throw new UserFacingException("Конец фрагмента должен быть позже начала");
    }
    return range;
  }

  private static String mime(Path file) {
    try {
      String m = Files.probeContentType(file);
      if (m != null) {
        return m;
      }
    } catch (IOException ignored) {
      // по расширению ниже
    }
    String n = file.getFileName().toString().toLowerCase();
    return n.endsWith(".mp4") ? "video/mp4" : n.endsWith(".mp3") ? "audio/mpeg" : n.endsWith(".webm") ? "video/webm"
        : n.endsWith(".webp") ? "image/webp" : n.endsWith(".pdf") ? "application/pdf" : "application/octet-stream";
  }

  private static void cleanup(File f) {
    if (f != null) {
      try {
        Files.deleteIfExists(f.toPath());
      } catch (IOException ignored) {
        // временный файл
      }
    }
  }

  private static void deleteDir(Path dir) {
    if (dir == null) {
      return;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // временный каталог
    }
  }
}
