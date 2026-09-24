package ru.whitebeef.beefsavebot.service.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LinkRequestTest {

  @Test
  void extractsLinkFromMention() {
    LinkRequest request = LinkRequest.parse("@BeefSaveBot https://youtu.be/dQw4w9WgXcQ");
    assertEquals("https://youtu.be/dQw4w9WgXcQ", request.url());
    assertNull(request.crop());
  }

  @Test
  void extractsLinkFromSharedText() {
    LinkRequest request = LinkRequest.parse(
        "Смотри какое видео https://www.tiktok.com/@user/video/123 ахах");
    assertEquals("https://www.tiktok.com/@user/video/123", request.url());
    assertNull(request.crop());
  }

  @Test
  void parsesCropAfterLink() {
    LinkRequest request = LinkRequest.parse("@bot https://youtu.be/x 0:10 0:25");
    assertEquals(10, request.crop().start().seconds(), 1e-9);
    assertEquals(25, request.crop().end().seconds(), 1e-9);

    LinkRequest dashed = LinkRequest.parse("https://youtu.be/x 1:00-1:30");
    assertEquals(60, dashed.crop().start().seconds(), 1e-9);
    assertEquals(90, dashed.crop().end().seconds(), 1e-9);

    LinkRequest frames = LinkRequest.parse("https://youtu.be/x 0:00:01:05 0:00:02:10");
    assertEquals(5, frames.crop().start().frame());
  }

  @Test
  void handlesMissingOrBrokenInput() {
    assertNull(LinkRequest.parse("просто текст"));
    assertNull(LinkRequest.parse(null));
    assertThrows(UserFacingException.class,
        () -> LinkRequest.parse("https://youtu.be/x 0:30 0:10"));
    assertThrows(UserFacingException.class,
        () -> LinkRequest.parse("https://youtu.be/x 1:75 2:00"));
  }
}
