package ru.whitebeef.beefsavebot.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.whitebeef.beefsavebot.entity.UserInfo;

public interface UserInfoRepository extends JpaRepository<UserInfo, Long> {

  Optional<UserInfo> findByTelegramUserId(Long telegramUserId);

}