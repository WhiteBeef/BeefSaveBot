package ru.whitebeef.beefsavebot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.model.RequestType;
import ru.whitebeef.beefsavebot.repository.RequestLogRepository;

@Service
@RequiredArgsConstructor
public class RequestService {

  private static final int ERROR_MESSAGE_LIMIT = 2000;

  private final RequestLogRepository requestLogRepository;

  @Transactional
  public RequestLog saveRequest(UserInfo userInfo, RequestType requestType, String text,
      Quality quality, OutputFormat outputFormat) {
    return requestLogRepository.save(RequestLog.builder()
        .userInfo(userInfo)
        .url(text)
        .requestType(requestType)
        .quality(quality)
        .outputFormat(outputFormat)
        .downloaded(false)
        .build());
  }

  @Transactional
  public RequestLog markDownloaded(RequestLog requestLog, long fileSize) {
    requestLog.setDownloaded(true);
    requestLog.setFileSize(fileSize);
    requestLog.setErrorMessage(null);
    return requestLogRepository.save(requestLog);
  }

  @Transactional
  public RequestLog markFailed(RequestLog requestLog, String errorMessage) {
    String message = errorMessage == null ? "Неизвестная ошибка" : errorMessage;
    if (message.length() > ERROR_MESSAGE_LIMIT) {
      message = message.substring(0, ERROR_MESSAGE_LIMIT);
    }
    requestLog.setErrorMessage(message);
    return requestLogRepository.save(requestLog);
  }

  @Transactional
  public RequestLog save(RequestLog requestLog) {
    return requestLogRepository.save(requestLog);
  }

}
