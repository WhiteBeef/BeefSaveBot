package ru.whitebeef.beefsavebot.service.media;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.service.download.YtDlpClient;

/**
 * Отправляет готовый файл в чат. Файл больше лимита Telegram режется на части, и каждая уходит
 * отдельным сообщением с подписью «Часть i/n».
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaSender {

  public static final long TELEGRAM_UPLOAD_LIMIT = 50L * 1024 * 1024;

  private final MediaProcessingService mediaProcessingService;

  /**
   * @param replyTo  на какое сообщение ответить, может быть {@code null}
   * @param silent   без звука уведомления (для служебного чата)
   * @return отправленные сообщения по порядку; одно, если резать не пришлось
   */
  public List<Message> send(AbsSender bot, String chatId, Integer replyTo, File file,
      OutputFormat format, boolean silent)
      throws TelegramApiException, IOException, InterruptedException {
    long size = Files.size(file.toPath());
    log.info("Размер файла: {} bytes", size);
    if (size <= TELEGRAM_UPLOAD_LIMIT) {
      return List.of(sendOne(bot, chatId, replyTo, file, format, null, null, silent));
    }
    // Исполнитель и название берём у целого файла: у частей тегов может не быть
    MediaProcessingService.AudioTags tags = format == OutputFormat.MP3
        ? mediaProcessingService.readAudioTags(file) : null;
    List<File> parts = mediaProcessingService.splitIntoParts(file, format, TELEGRAM_UPLOAD_LIMIT);
    try {
      List<Message> sent = new ArrayList<>();
      for (int i = 0; i < parts.size(); i++) {
        String caption = "Часть " + (i + 1) + "/" + parts.size();
        MediaProcessingService.AudioTags partTags = tags == null ? null
            : new MediaProcessingService.AudioTags(tags.performer(),
                tags.title() + " (" + caption.toLowerCase() + ")");
        sent.add(sendOne(bot, chatId, replyTo, parts.get(i), format, caption, partTags,
            silent));
      }
      return sent;
    } finally {
      YtDlpClient.deleteDirectory(parts.get(0).getParentFile().toPath());
    }
  }

  private Message sendOne(AbsSender bot, String chatId, Integer replyTo, File file,
      OutputFormat format, String caption, MediaProcessingService.AudioTags tags, boolean silent)
      throws TelegramApiException {
    InputFile inputFile = new InputFile(file);
    return switch (format) {
      case MP3 -> {
        // Передаём исполнителя и название явно: так Telegram покажет их даже при кривых тегах
        MediaProcessingService.AudioTags audioTags = tags != null ? tags
            : mediaProcessingService.readAudioTags(file);
        yield bot.execute(SendAudio.builder()
            .chatId(chatId)
            .audio(inputFile)
            .performer(audioTags.performer())
            .title(audioTags.title())
            .caption(caption)
            .replyToMessageId(replyTo)
            .allowSendingWithoutReply(replyTo == null ? null : true)
            .disableNotification(silent ? true : null)
            .build());
      }
      case MP4 -> {
        // Размеры и длительность явно: иначе плеер на iOS может показать неверные пропорции
        MediaProcessingService.VideoInfo info = mediaProcessingService.videoInfo(file);
        yield bot.execute(SendVideo.builder()
            .chatId(chatId)
            .video(inputFile)
            .supportsStreaming(true)
            .width(info == null ? null : info.width())
            .height(info == null ? null : info.height())
            .duration(info == null ? null : info.durationSeconds())
            .caption(caption)
            .replyToMessageId(replyTo)
            .allowSendingWithoutReply(replyTo == null ? null : true)
            .disableNotification(silent ? true : null)
            .build());
      }
      case WEBM, WEBP -> bot.execute(SendDocument.builder()
          .chatId(chatId)
          .document(inputFile)
          .caption(caption)
          .replyToMessageId(replyTo)
          .allowSendingWithoutReply(replyTo == null ? null : true)
          .disableNotification(silent ? true : null)
          .build());
    };
  }
}
