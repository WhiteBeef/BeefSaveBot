package ru.whitebeef.beefsavebot.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.whitebeef.beefsavebot.entity.RequestLog;
import ru.whitebeef.beefsavebot.entity.UserInfo;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

  long countByRequestedAtAfter(LocalDateTime since);

  long countByDownloadedTrue();

  long countByDownloadedTrueAndRequestedAtAfter(LocalDateTime since);

  long countByErrorMessageIsNotNull();

  long countByErrorMessageIsNotNullAndRequestedAtAfter(LocalDateTime since);

  long countByUserInfo(UserInfo userInfo);

  @EntityGraph(attributePaths = "userInfo")
  Page<RequestLog> findAllByOrderByRequestedAtDesc(Pageable pageable);

  @EntityGraph(attributePaths = "userInfo")
  Page<RequestLog> findByUrlContainingIgnoreCaseOrderByRequestedAtDesc(String text,
      Pageable pageable);

  @EntityGraph(attributePaths = "userInfo")
  Page<RequestLog> findByUserInfoOrderByRequestedAtDesc(UserInfo userInfo, Pageable pageable);

  @EntityGraph(attributePaths = "userInfo")
  Page<RequestLog> findByErrorMessageIsNotNullOrderByRequestedAtDesc(Pageable pageable);

  @EntityGraph(attributePaths = "userInfo")
  List<RequestLog> findAllByOrderByIdAsc();

  @Query("select r.requestType, count(r) from RequestLog r group by r.requestType order by count(r) desc")
  List<Object[]> countGroupedByType();

  @Query("select r.outputFormat, count(r) from RequestLog r where r.outputFormat is not null "
      + "group by r.outputFormat order by count(r) desc")
  List<Object[]> countGroupedByFormat();

  @Query(value = """
      SELECT CASE
               WHEN url ILIKE '%youtu%' THEN 'YouTube'
               WHEN url ILIKE '%tiktok.com%' THEN 'TikTok'
               WHEN url ILIKE '%instagram.com%' THEN 'Instagram'
               WHEN url ILIKE '%music.yandex%' OR url LIKE 'yandex-music-search:%' THEN 'Яндекс Музыка'
               ELSE 'Другое'
             END AS platform,
             COUNT(*) AS cnt
      FROM download_requests
      WHERE request_type IN ('DOWNLOAD', 'CROP', 'TRACK')
      GROUP BY platform
      ORDER BY cnt DESC
      """, nativeQuery = true)
  List<Object[]> countGroupedByPlatform();

  @Query("select u.telegramUserId, u.username, u.firstName, u.lastName, count(r) "
      + "from RequestLog r join r.userInfo u where r.requestedAt > :since "
      + "group by u.id, u.telegramUserId, u.username, u.firstName, u.lastName "
      + "order by count(r) desc")
  List<Object[]> findTopUsers(@Param("since") LocalDateTime since, Pageable pageable);
}
