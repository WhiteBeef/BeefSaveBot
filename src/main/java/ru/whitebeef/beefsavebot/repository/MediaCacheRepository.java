package ru.whitebeef.beefsavebot.repository;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.whitebeef.beefsavebot.entity.MediaCacheEntry;

public interface MediaCacheRepository extends JpaRepository<MediaCacheEntry, String> {

  @Modifying
  @Query("delete from MediaCacheEntry e where e.createdAt < :border")
  int deleteOlderThan(@Param("border") LocalDateTime border);
}
