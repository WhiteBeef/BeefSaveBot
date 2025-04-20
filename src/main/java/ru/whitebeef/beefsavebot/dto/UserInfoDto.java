package ru.whitebeef.beefsavebot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserInfoDto {

  private Long telegramUserId;
  private String username;
  private String firstName;
  private String lastName;

}
