package ru.whitebeef.beefsavebot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RequestDto {

  private UserInfoDto userInfoDto;
  private String url;

}
