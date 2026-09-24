package ru.whitebeef.beefsavebot.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.whitebeef.beefsavebot.entity.UserInfo;

public interface UserInfoRepository extends JpaRepository<UserInfo, Long> {

  Optional<UserInfo> findByTelegramUserId(Long telegramUserId);

  Optional<UserInfo> findFirstByUsernameIgnoreCase(String username);

  long countByCreatedAtAfter(LocalDateTime since);

  long countByLastSeenAtAfter(LocalDateTime since);

  long countByBannedTrue();

  @Query(value = "select u from UserInfo u order by u.lastSeenAt desc nulls last, u.id desc",
      countQuery = "select count(u) from UserInfo u")
  Page<UserInfo> findAllByActivity(Pageable pageable);

  @Query("select u.telegramUserId from UserInfo u where u.banned = false")
  List<Long> findNotBannedTelegramIds();

}
