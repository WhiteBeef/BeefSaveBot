package ru.whitebeef.beefsavebot.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import ru.whitebeef.beefsavebot.dto.RequestDto;
import ru.whitebeef.beefsavebot.entity.RequestLog;

@Mapper(componentModel = "spring", uses = UserInfoMapper.class)
public interface RequestLogMapper {

  RequestLog enrich(@MappingTarget RequestLog requestLog, RequestDto requestDto);

  RequestLog toEntity(RequestDto requestDto);

  RequestDto toDto(RequestLog requestLog);
}
