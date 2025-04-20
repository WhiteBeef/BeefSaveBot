package ru.whitebeef.beefsavebot.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.UUID;

@Service
public class DownloadService {

    public File downloadVideo(String url) throws IOException, InterruptedException {
        String filename = "video_" + UUID.randomUUID() + ".mp4";

        ProcessBuilder builder = new ProcessBuilder(
            "yt-dlp", "-f", "best[height<=720][ext=mp4]", "-o", filename, url
        );
        builder.inheritIO();

        Process process = builder.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("yt-dlp завершился с ошибкой, код: " + exitCode);
        }

        File file = new File(filename);
        if (!file.exists()) {
            throw new RuntimeException("Загруженный файл не найден: " + filename);
        }

        return file;
    }
}