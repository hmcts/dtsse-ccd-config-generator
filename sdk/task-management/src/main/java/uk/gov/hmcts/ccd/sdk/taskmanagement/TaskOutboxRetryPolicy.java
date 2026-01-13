package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class TaskOutboxRetryPolicy {

  private final int maxAttempts;
  private final double multiplier;
  private final long initialDelayMillis;
  private final long maxDelayMillis;

  public TaskOutboxRetryPolicy(TaskManagementProperties properties) {
    this.maxAttempts = properties.getOutbox().getRetry().getMaxAttempts();
    this.multiplier = properties.getOutbox().getRetry().getMultiplier();
    this.initialDelayMillis = properties.getOutbox().getRetry().getInitialDelay().toMillis();
    this.maxDelayMillis = properties.getOutbox().getRetry().getMaxDelay().toMillis();
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public LocalDateTime nextAttemptAt(int attemptCount, LocalDateTime now) {
    if (maxAttempts > 0 && attemptCount >= maxAttempts) {
      return null;
    }
    double delayMillis = initialDelayMillis;
    for (int attempt = 1; attempt < attemptCount; attempt++) {
      delayMillis = delayMillis * multiplier;
    }
    if (maxDelayMillis > 0) {
      delayMillis = Math.min(delayMillis, maxDelayMillis);
    }
    return now.plus(Math.round(delayMillis), ChronoUnit.MILLIS);
  }
}
