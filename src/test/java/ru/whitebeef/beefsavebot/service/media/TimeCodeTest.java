package ru.whitebeef.beefsavebot.service.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TimeCodeTest {

  @Test
  void parsesSeconds() {
    assertEquals(83, TimeCode.parse("83").toSeconds(null), 1e-9);
    assertEquals(83.5, TimeCode.parse("83.5").toSeconds(null), 1e-9);
    assertEquals(83.5, TimeCode.parse("83,5").toSeconds(null), 1e-9);
  }

  @Test
  void parsesMinutesAndHours() {
    assertEquals(83.48, TimeCode.parse("1:23.480").toSeconds(null), 1e-9);
    assertEquals(3723, TimeCode.parse("1:02:03").toSeconds(null), 1e-9);
    assertEquals(4992, TimeCode.parse("1:23:12").toSeconds(null), 1e-9);
    assertNull(TimeCode.parse("1:23:12").frame());
  }

  @Test
  void parsesFrames() {
    TimeCode timeCode = TimeCode.parse("0:01:23:12");
    assertEquals(12, timeCode.frame());
    assertEquals(83 + 12 / 25.0, timeCode.toSeconds(25.0), 1e-9);
    assertEquals(83 + 12 / 29.97, TimeCode.parse("0:01:23;12").toSeconds(29.97), 1e-9);
  }

  @Test
  void rejectsInvalidValues() {
    assertThrows(UserFacingException.class, () -> TimeCode.parse("abc"));
    assertThrows(UserFacingException.class, () -> TimeCode.parse("1:75"));
    assertThrows(UserFacingException.class, () -> TimeCode.parse("1:23;12"));
    assertThrows(UserFacingException.class, () -> TimeCode.parse("0:00:01:30").toSeconds(25.0));
    assertThrows(UserFacingException.class, () -> TimeCode.parse("0:00:01:10").toSeconds(null));
  }
}
