package ru.whitebeef.beefsavebot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.whitebeef.beefsavebot.dto.RequestDto;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.repository.RequestLogRepository;

@Service
@RequiredArgsConstructor
public class RequestService {

  private final RequestLogRepository requestLogRepository;
  private final UserService userService;

  @Transactional
  public void saveRequest(RequestDto requestDto) {
    UserInfo userinfo = userService.updateOrCreate(requestDto.getUserInfoDto());

    requestLogRepository.save(RequestLog.builder()
        .userInfo(userinfo)
        .url(requestDto.getUrl())
        .build());
  }

}

