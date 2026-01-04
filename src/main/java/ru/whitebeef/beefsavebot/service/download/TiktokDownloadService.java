package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Service
@Slf4j
public class TiktokDownloadService implements DownloadService {

    private static final Long MAX_RESOLUTION = 1080L;
    private static final Long MAX_BYTES = 50L * 1024 * 1024;
    private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
                    "^https:\\/\\/(www\\.)?(vm\\.|vt\\.)?tiktok\\.com\\/.+$")
            .asMatchPredicate();

    public File downloadVideo(String url) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            log.info("Запрос на получение метаданных");
            ProcessBuilder metadataProcessBuilder = new ProcessBuilder("yt-dlp",
                    "--no-playlist",
                    "-j", url);
            Process metadataProcess = metadataProcessBuilder.start();
            String metadataJson = new String(metadataProcess.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (metadataProcess.waitFor() != 0) {
                throw new RuntimeException("Не удалось получить метаданные");
            }
            log.info("Метаданные получены");
            JsonNode root = mapper.readTree(metadataJson);
            JsonNode formats = root.path("formats");
            if (!formats.isArray()) {
                throw new RuntimeException("Нет массива formats в JSON");
            }

            List<Candidate> candidates = new ArrayList<>();
            List<JsonNode> videos = new ArrayList<>(), audios = new ArrayList<>(), muxeds = new ArrayList<>();

            for (JsonNode format : formats) {
                String id = format.path("format_id").asText(null);
                if (id == null) {
                    continue;
                }
                long size = format.has("filesize") ? format.get("filesize").asLong(-1)
                        : format.has("filesize_approx") ? format.get("filesize_approx").asLong(-1)
                        : -1;
                if (size < 0) {
                    continue;
                }
                String audioCodec = format.path("acodec").asText("none");
                String videoCodec = format.path("vcodec").asText("none");
                boolean hasVideo = !"none".equals(videoCodec);
                boolean hasAudio = !"none".equals(audioCodec);
                if (hasVideo && hasAudio) {
                    muxeds.add(format);
                } else if (hasVideo) {
                    videos.add(format);
                } else if (hasAudio) {
                    audios.add(format);
                }
            }

            List<JsonNode> compatibleMuxeds = muxeds.stream()
                    .filter(m -> m.path("vcodec").asText().startsWith("h265") || m.path("vcodec").asText().startsWith("h264")) // H.264
                    .filter(m -> m.path("acodec").asText().startsWith("aac")).toList();

            List<JsonNode> h264Videos = videos.stream()
                    .filter(v -> v.path("vcodec").asText().startsWith("h265") || v.path("vcodec").asText().startsWith("h264")).toList();

            List<JsonNode> aacAudios = audios.stream()
                    .filter(a -> a.path("acodec").asText().startsWith("aac")).toList();

            for (JsonNode muxed : compatibleMuxeds) {
                int muxedHeight = muxed.path("height").asInt(0);
                int muxedWidth = muxed.path("width").asInt(0);
                int minDim = Math.min(muxedWidth, muxedHeight);
                long muxedSize = muxed.has("filesize") ? muxed.get("filesize").asLong()
                        : muxed.get("filesize_approx").asLong();
                if (minDim <= MAX_RESOLUTION && muxedSize <= MAX_BYTES) {
                    candidates.add(new Candidate(muxed.path("format_id").asText(), minDim));
                }
            }

            if (candidates.isEmpty()) {
                for (JsonNode video : h264Videos) {
                    int videoHeight = video.path("height").asInt(0);
                    int videoWidth = video.path("width").asInt(0);
                    int minDim = Math.min(videoWidth, videoHeight);
                    if (minDim > MAX_RESOLUTION) {
                        continue;
                    }
                    String vid = video.path("format_id").asText();
                    long videoSize = video.has("filesize") ? video.get("filesize").asLong()
                            : video.get("filesize_approx").asLong();
                    for (JsonNode audio : aacAudios) {
                        String aid = audio.path("format_id").asText();
                        long audioSize = audio.has("filesize") ? audio.get("filesize").asLong()
                                : audio.get("filesize_approx").asLong();
                        if (videoSize + audioSize <= MAX_BYTES) {
                            candidates.add(new Candidate(vid + "+" + aid, minDim));
                        }
                    }
                }
            }

            candidates.sort((a, b) -> Integer.compare(b.resolution, a.resolution));
            if (candidates.isEmpty()) {
                throw new RuntimeException("Ни один кандидат ≤50MB не найден");
            }
            log.info("Кандидаты собраны. Количество: {}", candidates.size());
            for (Candidate c : candidates) {
                log.info("Попытка скачать видео с разрешением {}", c.resolution);
                String base = "video_" + UUID.randomUUID();
                String outTpl = base + ".%(ext)s";

                ProcessBuilder pbDl = new ProcessBuilder(
                        "yt-dlp",
                        "--no-playlist",
                        "--merge-output-format", "mp4",
                        "-f", c.combo,
                        "-o", outTpl,
                        url
                );
                pbDl.inheritIO();
                Process dl = pbDl.start();
                if (dl.waitFor() != 0) {
                    continue;
                }

                File file = new File(base + ".mp4");
                if (!file.exists()) {
                    continue;
                }
                long actual = file.length();
                if (actual <= MAX_BYTES) {
                    return file;
                } else {
                    file.delete();
                }
            }

            throw new RuntimeException("Все подходящие кандидаты превысили 50MB после загрузки");
        } catch (IOException | InterruptedException e) {
            log.error("Ошибка при загрузке видео с помощью yt-dlp", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean canDownloadVideo(String url) {
        return PATTERN_PREDICATE.test(url);
    }

    @Override
    public List<String> getSupportedSites() {
        return List.of("Tiktok");
    }

    @Data
    @AllArgsConstructor
    private static class Candidate {
        private String combo;
        private int resolution;
    }
}