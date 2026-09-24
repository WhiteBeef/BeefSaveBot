package ru.whitebeef.beefsavebot.dto;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Экран админки: текст (HTML) и кнопки под ним. Навигация по кнопкам редактирует то же сообщение.
 */
public record Screen(String text, InlineKeyboardMarkup keyboard) {

}
