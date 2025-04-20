package ru.whitebeef.beefsavebot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.whitebeef.beefsavebot.entity.RequestLog;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {
}