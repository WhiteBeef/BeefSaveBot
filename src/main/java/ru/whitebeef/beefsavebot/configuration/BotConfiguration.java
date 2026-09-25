package ru.whitebeef.beefsavebot.configuration;

import java.time.ZoneId;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
public class BotConfiguration {
    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.bot.username}")
    private String botUsername;

    /**
     * Telegram ID единственного администратора. Пусто — админка выключена.
     */
    @Value("${telegram.bot.admin-id:}")
    private String adminId;

    /**
     * Служебный чат, куда бот загружает файлы для инлайн-режима (сообщения сразу удаляются).
     * Пусто — используется чат администратора.
     */
    @Value("${telegram.bot.storage-chat-id:}")
    private String storageChatId;

    @Value("${telegram.bot.author:@WhiteBeef}")
    private String author;

    @Value("${telegram.bot.github-url:https://github.com/WhiteBeef/BeefSaveBot}")
    private String githubUrl;

    /**
     * Часовой пояс, в котором админке показываются даты.
     */
    @Value("${telegram.bot.timezone:Europe/Moscow}")
    private String timezone;

    public boolean isAdmin(Long telegramUserId) {
        return telegramUserId != null && adminId != null && !adminId.isBlank()
            && adminId.trim().equals(telegramUserId.toString());
    }

    /**
     * Чат для загрузки файлов инлайн-режима или {@code null}, если не настроен.
     */
    public String getEffectiveStorageChatId() {
        if (storageChatId != null && !storageChatId.isBlank()) {
            return storageChatId.trim();
        }
        return adminId == null || adminId.isBlank() ? null : adminId.trim();
    }

    public ZoneId getZoneId() {
        return ZoneId.of(timezone);
    }
}
