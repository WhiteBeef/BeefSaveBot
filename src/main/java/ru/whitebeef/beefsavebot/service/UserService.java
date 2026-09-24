package ru.whitebeef.beefsavebot.service;

import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.whitebeef.beefsavebot.dto.UserInfoDto;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.mappers.UserInfoMapper;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.repository.UserInfoRepository;

@Service
@RequiredArgsConstructor
public class UserService {

  private final UserInfoRepository userInfoRepository;
  private final UserInfoMapper userInfoMapper;

  @Transactional
  public UserInfo updateOrCreate(UserInfoDto userInfoDto) {
    Optional<UserInfo> optionalUserInfo = userInfoRepository.findByTelegramUserId(
        userInfoDto.getTelegramUserId());

    UserInfo userInfo = userInfoMapper.updateEntity(
        optionalUserInfo.orElseGet(() -> UserInfo.builder().build()), userInfoDto);
    userInfo.setLastSeenAt(LocalDateTime.now());
    return userInfoRepository.save(userInfo);
  }

  @Transactional
  public UserInfo updateSettings(Long telegramUserId, Quality quality, OutputFormat outputFormat) {
    UserInfo userInfo = userInfoRepository.findByTelegramUserId(telegramUserId)
        .orElseThrow(() -> new IllegalStateException("Пользователь не найден: " + telegramUserId));
    if (quality != null) {
      userInfo.setQuality(quality);
    }
    if (outputFormat != null) {
      userInfo.setOutputFormat(outputFormat);
    }
    return userInfoRepository.save(userInfo);
  }

  @Transactional(readOnly = true)
  public Optional<UserInfo> findByTelegramId(Long telegramUserId) {
    return userInfoRepository.findByTelegramUserId(telegramUserId);
  }

  /**
   * Ищет пользователя по Telegram ID или @username.
   */
  @Transactional(readOnly = true)
  public Optional<UserInfo> find(String idOrUsername) {
    String value = idOrUsername.trim();
    if (value.startsWith("@")) {
      return userInfoRepository.findFirstByUsernameIgnoreCase(value.substring(1));
    }
    try {
      return userInfoRepository.findByTelegramUserId(Long.parseLong(value));
    } catch (NumberFormatException e) {
      return userInfoRepository.findFirstByUsernameIgnoreCase(value);
    }
  }

  @Transactional
  public Optional<UserInfo> setBanned(String idOrUsername, boolean banned) {
    return find(idOrUsername).map(userInfo -> {
      userInfo.setBanned(banned);
      return userInfoRepository.save(userInfo);
    });
  }

}
