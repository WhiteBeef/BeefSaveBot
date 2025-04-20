package ru.whitebeef.beefsavebot.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import ru.whitebeef.beefsavebot.dto.UserInfoDto;
import ru.whitebeef.beefsavebot.entity.UserInfo;

@Mapper(componentModel = "spring")
public interface UserInfoMapper {

  UserInfo toEntity(UserInfoDto userInfoDto);

  UserInfoDto toDto(UserInfo userInfo);

  UserInfo updateEntity(@MappingTarget UserInfo userInfo, UserInfoDto userInfoDto);

}
