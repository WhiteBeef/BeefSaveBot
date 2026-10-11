package ru.whitebeef.beefsavebot.api;

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

  static final Duration KEEP = Duration.ofHours(24);

  public enum Status { QUEUED, RUNNING, DONE, FAILED }

  public record Job(String id, String what, Status status, String result, String error, Instant createdAt,
                    Instant finishedAt) {
  }

  @FunctionalInterface
  public interface Work {
    /** @return итог словами («Отправил видео 23 МБ») */
    String run() throws Exception;
  }

  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newFixedThreadPool(4);

  public Job submit(String what, Work work) {
    Instant now = Instant.now();
    jobs.values().removeIf(j -> j.createdAt().plus(KEEP).isBefore(now));
    String id = UUID.randomUUID().toString().substring(0, 12);
    jobs.put(id, new Job(id, what, Status.QUEUED, null, null, now, null));
    executor.execute(() -> {
      jobs.computeIfPresent(id, (k, j) -> new Job(id, what, Status.RUNNING, null, null, j.createdAt(), null));
      try {
        String result = work.run();
        jobs.computeIfPresent(id, (k, j) -> new Job(id, what, Status.DONE, result, null, j.createdAt(),
            Instant.now()));
      } catch (Exception e) {
        String error = e instanceof UserFacingException ? e.getMessage() : "Не вышло: " + e.getMessage();
        log.warn("API-задача {} ({}) не выполнена: {}", id, what, e.toString());
        jobs.computeIfPresent(id, (k, j) -> new Job(id, what, Status.FAILED, null, error, j.createdAt(),
            Instant.now()));
      }
    });
    return jobs.get(id);
  }

  public Job get(String id) {
    return jobs.get(id);
  }
}
