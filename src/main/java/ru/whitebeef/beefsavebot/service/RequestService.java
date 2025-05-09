package ru.whitebeef.beefsavebot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.whitebeef.beefsavebot.dto.RequestDto;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.mappers.RequestLogMapper;
import ru.whitebeef.beefsavebot.repository.RequestLogRepository;

@Service
@RequiredArgsConstructor
public class RequestService {

  private final RequestLogRepository requestLogRepository;
  private final UserService userService;
  private final RequestLogMapper requestLogMapper;

  @Transactional
  public RequestLog saveRequest(RequestDto requestDto) {
    UserInfo userinfo = userService.updateOrCreate(requestDto.getUserInfoDto());

    return requestLogRepository.save(
        requestLogMapper.enrich(RequestLog.builder().userInfo(userinfo).build(), requestDto));
  }

  @Transactional
  public RequestLog save(RequestLog requestLog) {
    return requestLogRepository.save(requestLog);
  }

}

