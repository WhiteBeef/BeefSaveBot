package ru.whitebeef.beefsavebot.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.whitebeef.beefsavebot.dto.UserInfoDto;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.mappers.UserInfoMapper;
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

    UserInfo userInfo = userInfoRepository.save(
        userInfoMapper.updateEntity(optionalUserInfo.orElseGet(() -> UserInfo.builder().build()),
            userInfoDto));
    return userInfo;
  }

}
