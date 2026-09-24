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

    @Value("${telegram.bot.author:@WhiteBeef}")
    private String author;

    /**
     * Часовой пояс, в котором админке показываются даты.
     */
    @Value("${telegram.bot.timezone:Europe/Moscow}")
    private String timezone;

    public boolean isAdmin(Long telegramUserId) {
        return telegramUserId != null && adminId != null && !adminId.isBlank()
            && adminId.trim().equals(telegramUserId.toString());
    }

    public ZoneId getZoneId() {
        return ZoneId.of(timezone);
    }
}
