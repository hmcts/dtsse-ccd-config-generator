package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
public class TaskOutboxFailureLogPoller {
  private final TaskOutboxRepository repository;
  private final int batchSize;
  private final AtomicLong lastLoggedHistoryId = new AtomicLong();

  public TaskOutboxFailureLogPoller(TaskOutboxRepository repository, int batchSize) {
    this.repository = repository;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${task-management.outbox.failure-log-poller.delay:60000}")
  public void poll() {
    long afterHistoryId = lastLoggedHistoryId.get();
    List<TaskOutboxFailureLogEntry> failures = repository.findFailedHistoryAfter(afterHistoryId, batchSize);
    if (failures.isEmpty()) {
      return;
    }

    failures.forEach(this::logFailure);
    lastLoggedHistoryId.updateAndGet(current -> Math.max(current, failures.getLast().historyId()));
  }

  private void logFailure(TaskOutboxFailureLogEntry failure) {
    log.warn(
        "Task outbox failure historyId={} taskOutboxId={} caseId={} requestedAction={} responseCode={} "
            + "created={} error={} payload={}",
        failure.historyId(),
        failure.taskOutboxId(),
        failure.caseId(),
        failure.requestedAction(),
        failure.responseCode(),
        failure.created(),
        failure.error(),
        failure.payload()
    );
  }
}
