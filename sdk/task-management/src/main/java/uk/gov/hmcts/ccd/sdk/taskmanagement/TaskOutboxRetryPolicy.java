package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class TaskOutboxRetryPolicy {

  private final int maxAttempts;
  private final int multiplier;
  private final long initialDelaySeconds;
  private final long maxDelaySeconds;

  public TaskOutboxRetryPolicy(TaskManagementProperties properties) {
    this.maxAttempts = properties.getOutbox().getRetry().getMaxAttempts();
    this.multiplier = (int) properties.getOutbox().getRetry().getMultiplier();
    this.initialDelaySeconds = properties.getOutbox().getRetry().getInitialDelay().getSeconds();
    this.maxDelaySeconds = properties.getOutbox().getRetry().getMaxDelay().getSeconds();
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public LocalDateTime nextAttemptAt(int attemptCount, LocalDateTime now) {
    if (maxAttempts > 0 && attemptCount >= maxAttempts) {
      return null;
    }
    long delay = initialDelaySeconds;
    for (int attempt = 1; attempt < attemptCount; attempt++) {
      delay = delay * multiplier;
    }
    if (maxDelaySeconds > 0) {
      delay = Math.min(delay, maxDelaySeconds);
    }
    return now.plus(delay, ChronoUnit.SECONDS);
  }
}
