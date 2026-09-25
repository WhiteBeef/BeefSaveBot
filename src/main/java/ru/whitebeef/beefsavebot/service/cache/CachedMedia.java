package ru.whitebeef.beefsavebot.service.cache;

import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAnimation;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAudio;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo;

/**
 * Файл, уже загруженный в Telegram: его можно отправить повторно по file_id без скачивания.
 * Тип берётся из ответа Telegram (например, видео без звука он сохраняет как GIF-анимацию).
 */
public record CachedMedia(String fileId, Kind kind, Long fileSize) {

  public enum Kind { VIDEO, ANIMATION, AUDIO, DOCUMENT }

  /**
   * @return загруженный файл из отправленного сообщения или {@code null}, если файла в нём нет
   */
  public static CachedMedia of(Message message) {
    if (message == null) {
      return null;
    }
    if (message.getVideo() != null) {
      return new CachedMedia(message.getVideo().getFileId(), Kind.VIDEO,
          message.getVideo().getFileSize());
    }
    if (message.getAnimation() != null) {
      return new CachedMedia(message.getAnimation().getFileId(), Kind.ANIMATION,
          message.getAnimation().getFileSize());
    }
    if (message.getAudio() != null) {
      return new CachedMedia(message.getAudio().getFileId(), Kind.AUDIO,
          message.getAudio().getFileSize());
    }
    if (message.getDocument() != null) {
      return new CachedMedia(message.getDocument().getFileId(), Kind.DOCUMENT,
          message.getDocument().getFileSize());
    }
    return null;
  }

  public InputMedia toInputMedia() {
    InputMedia media = switch (kind) {
      case VIDEO -> {
        InputMediaVideo video = new InputMediaVideo();
        video.setSupportsStreaming(true);
        yield video;
      }
      case ANIMATION -> new InputMediaAnimation();
      case AUDIO -> new InputMediaAudio();
      case DOCUMENT -> new InputMediaDocument();
    };
    media.setMedia(fileId);
    return media;
  }
}
