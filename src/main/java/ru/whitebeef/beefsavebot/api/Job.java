package ru.whitebeef.beefsavebot.api;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Job {

  private final String id;
  private final String what;
  private final JobStatus status;
  private final String result;
  private final String error;
  private final Instant createdAt;
  private final Instant finishedAt;
}
