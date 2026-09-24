package ru.whitebeef.beefsavebot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import ru.whitebeef.beefsavebot.service.media.LinkRequest;

class ReplyLinkTest {

  @Test
  void findsPlainLinkInText() {
    Message message = new Message();
    message.setText("глянь https://www.instagram.com/reel/abc/ ахаха");
    assertEquals("https://www.instagram.com/reel/abc/",
        LinkRequest.parse(TelegramBotService.linkSource(message)).url());
  }

  @Test
  void findsLinkHiddenUnderText() {
    Message message = new Message();
    message.setText("вот это видео");
    MessageEntity entity = new MessageEntity();
    entity.setType("text_link");
    entity.setOffset(4);
    entity.setLength(3);
    entity.setUrl("https://youtu.be/dQw4w9WgXcQ");
    message.setEntities(List.of(entity));
    assertEquals("https://youtu.be/dQw4w9WgXcQ",
        LinkRequest.parse(TelegramBotService.linkSource(message)).url());
  }

  @Test
  void findsLinkInMediaCaption() {
    Message message = new Message();
    message.setCaption("источник: https://vt.tiktok.com/ZSbRvRstH/");
    assertEquals("https://vt.tiktok.com/ZSbRvRstH/",
        LinkRequest.parse(TelegramBotService.linkSource(message)).url());
  }

  @Test
  void timeCodesFromMentionApplyToRepliedLink() {
    // Так собирается запрос из «@бот 0:10 0:25» в ответ на сообщение со ссылкой
    String timeCodes = "@BeefSaveBot 0:10 0:25".replaceAll("@\\S+", " ").trim();
    LinkRequest request = LinkRequest.parse("https://youtu.be/x " + timeCodes);
    assertEquals(10, request.crop().start().seconds(), 1e-9);
    assertEquals(25, request.crop().end().seconds(), 1e-9);
  }
}
