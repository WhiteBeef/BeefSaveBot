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

        // Указываем yt-dlp скачать лучший MP4-формат с разрешением до 720p
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

    public InputStream downloadVideoStream(String url) throws IOException, InterruptedException {
        // Создаем пару связанных потоков для передачи данных
        PipedInputStream pipedInputStream = new PipedInputStream();
        PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream);

        // Настраиваем процесс yt-dlp
        ProcessBuilder builder = new ProcessBuilder(
            "yt-dlp", "-f", "best[height<=720][ext=mp4]", "-o", "-", url
        );
        builder.redirectErrorStream(true); // Перенаправляем stderr в stdout
        Process process = builder.start();

        // Поток для чтения вывода процесса и записи в PipedOutputStream
        Thread outputReaderThread = new Thread(() -> {
            try (InputStream processOutput = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = processOutput.read(buffer)) != -1) {
                    pipedOutputStream.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    pipedOutputStream.close(); // Закрываем поток после завершения
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        outputReaderThread.start();

        // Поток для ожидания завершения процесса и проверки кода выхода
        Thread processWaiterThread = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Ошибка выполнения yt-dlp, код: " + exitCode);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        processWaiterThread.start();

        // Возвращаем InputStream для немедленного чтения
        return pipedInputStream;
    }
}