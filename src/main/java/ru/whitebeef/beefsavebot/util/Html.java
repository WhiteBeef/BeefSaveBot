package ru.whitebeef.beefsavebot.util;

public final class Html {

  private Html() {
  }

  public static String escape(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
