package ru.whitebeef.beefsavebot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.ExternalReplyInfo;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.TextQuote;
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

  @Test
  void replySourceUsesRepliedMessage() {
    Message original = new Message();
    original.setText("https://www.instagram.com/reel/DdoLnWEI9dC/?stkn=dDlxdGh3OW94a2Zs");
    Message command = new Message();
    command.setText("/save");
    command.setReplyToMessage(original);
    assertEquals("https://www.instagram.com/reel/DdoLnWEI9dC/?stkn=dDlxdGh3OW94a2Zs",
        LinkRequest.parse(TelegramBotService.replySource(command)).url());
  }

  @Test
  void replySourceFallsBackToQuoteAndExternalReply() {
    Message quoted = new Message();
    quoted.setText("/save");
    TextQuote quote = new TextQuote();
    quote.setText("https://youtu.be/dQw4w9WgXcQ");
    quoted.setQuote(quote);
    assertEquals("https://youtu.be/dQw4w9WgXcQ",
        LinkRequest.parse(TelegramBotService.replySource(quoted)).url());

    Message external = new Message();
    external.setText("/save");
    ExternalReplyInfo info = new ExternalReplyInfo();
    LinkPreviewOptions preview = new LinkPreviewOptions();
    preview.setUrlField("https://vt.tiktok.com/ZSbRvRstH/");
    info.setLinkPreviewOptions(preview);
    external.setExternalReplyInfo(info);
    assertEquals("https://vt.tiktok.com/ZSbRvRstH/",
        LinkRequest.parse(TelegramBotService.replySource(external)).url());
  }

  @Test
  void notAReplyHasNoSource() {
    Message message = new Message();
    message.setText("/save");
    assertNull(TelegramBotService.replySource(message));
  }
}
