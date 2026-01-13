package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.Duration;
import java.time.LocalDateTime;

public class TaskOutboxRetryPolicy {

    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;
    private final int maxAttempts;

    public TaskOutboxRetryPolicy(TaskManagementProperties properties) {
        TaskManagementProperties.Retry retry = properties.getOutbox().getRetry();
        this.initialDelay = retry.getInitialDelay();
        this.maxDelay = retry.getMaxDelay();
        this.multiplier = retry.getMultiplier();
        this.maxAttempts = retry.getMaxAttempts();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public LocalDateTime nextAttemptAt(int nextAttemptCount, LocalDateTime now) {
        if (maxAttempts > 0 && nextAttemptCount >= maxAttempts) {
            return null;
        }

        long initialMs = Math.max(0, initialDelay.toMillis());
        double factor = Math.max(1.0, multiplier);
        double delayMs = initialMs * Math.pow(factor, Math.max(0, nextAttemptCount - 1));
        long maxMs = maxDelay.toMillis();
        if (maxMs > 0) {
            delayMs = Math.min(delayMs, maxMs);
        }

        return now.plusNanos((long) (delayMs * 1_000_000));
    }
}
