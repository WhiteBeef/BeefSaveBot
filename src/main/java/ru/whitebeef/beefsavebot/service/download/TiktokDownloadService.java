package ru.whitebeef.beefsavebot.service.download;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.whitebeef.beefsavebot.configuration.DownloadConfiguration;

@Service
@Slf4j
public class TiktokDownloadService extends AbstractYtDlpDownloadService {

    private static final Predicate<String> PATTERN_PREDICATE = Pattern.compile(
                    "^https:\\/\\/(www\\.)?(vm\\.|vt\\.)?tiktok\\.com\\/.+$")
            .asMatchPredicate();

    public TiktokDownloadService(DownloadConfiguration downloadConfiguration,
            YtDlpClient ytDlpClient) {
        super(downloadConfiguration, ytDlpClient);
    }

    @Override
    protected boolean isCompatibleVideoCodec(String codec) {
        return codec.startsWith("h265") || codec.startsWith("h264");
    }

    @Override
    protected boolean isCompatibleAudioCodec(String codec) {
        return codec.startsWith("aac");
    }

    /**
     * Вертикальные видео ограничиваем по меньшей стороне.
     */
    @Override
    protected int dimensionOf(JsonNode format) {
        return Math.min(format.path("width").asInt(0), format.path("height").asInt(0));
    }

    @Override
    protected int maxDimension() {
        return downloadConfiguration.getMaxResolution();
    }

    @Override
    public boolean canDownloadVideo(String url) {
        return PATTERN_PREDICATE.test(url);
    }

    @Override
    public List<String> getSupportedSites() {
        return List.of("Tiktok");
    }
}
