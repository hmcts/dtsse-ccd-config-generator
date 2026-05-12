package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxStatus;

@Slf4j
public class TaskOutboxCompletionAwaiter {

  private final TaskOutboxRepository repository;
  private final TaskManagementProperties properties;

  public TaskOutboxCompletionAwaiter(TaskOutboxRepository repository, TaskManagementProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  public void awaitProcessedAfterCommit(long outboxId) {
    if (!properties.getOutbox().getCompletion().isAwaitProcessed()) {
      return;
    }

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          awaitProcessed(outboxId);
        }
      });
      return;
    }

    awaitProcessed(outboxId);
  }

  public void awaitProcessed(long outboxId) {
    Duration timeout = properties.getOutbox().getCompletion().getTimeout();
    Duration pollInterval = properties.getOutbox().getCompletion().getPollInterval();
    Instant deadline = Instant.now().plus(timeout);

    while (Instant.now().isBefore(deadline)) {
      TaskOutboxStatus status = repository.findStatus(outboxId).orElse(null);
      if (status == null) {
        log.warn("Task outbox {} could not be found while waiting for completion", outboxId);
        return;
      }

      if (status == TaskOutboxStatus.PROCESSED) {
        log.info("Task outbox {} completed before response", outboxId);
        return;
      }

      if (!sleep(pollInterval, outboxId)) {
        return;
      }
    }

    log.warn(
        "Timed out waiting for task outbox {} to be processed after {}. Returning response anyway.",
        outboxId,
        timeout
    );
  }

  private boolean sleep(Duration pollInterval, long outboxId) {
    try {
      Thread.sleep(pollInterval.toMillis());
      return true;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting for task outbox {} to be processed. Returning response anyway.", outboxId);
      return false;
    }
  }
}
