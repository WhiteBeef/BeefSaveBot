package ru.whitebeef.beefsavebot.api;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.whitebeef.beefsavebot.service.media.UserFacingException;

/**
 * Задачи «прислать в Telegram»: скачать и обработать видео может занять минуты, поэтому API сразу
 * отвечает номером задачи, а итог видно по {@code GET /api/v1/jobs/{id}}. Хранятся в памяти сутки.
 */
@Slf4j
@Component
public class ApiJobs {

  private static final Duration KEEP = Duration.ofHours(24);

  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newFixedThreadPool(4);

  @PreDestroy
  public void shutdown() {
    executor.shutdown();
  }

  public Job submit(String what, JobWork work) {
    Instant now = Instant.now();
    jobs.values().removeIf(j -> j.getCreatedAt().plus(KEEP).isBefore(now));
    String id = UUID.randomUUID().toString().substring(0, 12);
    jobs.put(id, new Job(id, what, JobStatus.QUEUED, null, null, now, null));
    executor.execute(() -> {
      jobs.computeIfPresent(id, (k, j) -> new Job(id, what, JobStatus.RUNNING, null, null,
          j.getCreatedAt(), null));
      try {
        String result = work.run();
        jobs.computeIfPresent(id, (k, j) -> new Job(id, what, JobStatus.DONE, result, null,
            j.getCreatedAt(), Instant.now()));
      } catch (Exception e) {
        String error = e instanceof UserFacingException ? e.getMessage()
            : "Не вышло: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        if (e instanceof UserFacingException) {
          log.warn("API-задача {} ({}) не выполнена: {}", id, what, e.getMessage());
        } else {
          log.error("API-задача {} ({}) не выполнена", id, what, e);
        }
        jobs.computeIfPresent(id, (k, j) -> new Job(id, what, JobStatus.FAILED, null, error,
            j.getCreatedAt(), Instant.now()));
      }
    });
    return jobs.get(id);
  }

  public Job get(String id) {
    return jobs.get(id);
  }
}
