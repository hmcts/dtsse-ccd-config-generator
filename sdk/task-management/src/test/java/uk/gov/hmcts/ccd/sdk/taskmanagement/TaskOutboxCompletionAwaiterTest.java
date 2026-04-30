package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxStatus;

class TaskOutboxCompletionAwaiterTest {

  private final TaskOutboxRepository repository = mock(TaskOutboxRepository.class);
  private final TaskManagementProperties properties = new TaskManagementProperties();
  private final TaskOutboxCompletionAwaiter awaiter = new TaskOutboxCompletionAwaiter(repository, properties);

  @AfterEach
  void clearTransactionSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void awaitsUntilOutboxRowIsProcessed() {
    properties.getOutbox().getCompletion().setPollInterval(Duration.ofMillis(1));
    when(repository.findStatus(42L))
        .thenReturn(Optional.of(TaskOutboxStatus.NEW))
        .thenReturn(Optional.of(TaskOutboxStatus.PROCESSED));

    awaiter.awaitProcessed(42L);

    verify(repository, times(2)).findStatus(42L);
  }

  @Test
  void returnsAfterTimeoutWhenOutboxRowIsNotProcessedInTime() {
    properties.getOutbox().getCompletion().setTimeout(Duration.ofMillis(5));
    properties.getOutbox().getCompletion().setPollInterval(Duration.ofMillis(1));
    when(repository.findStatus(42L)).thenReturn(Optional.of(TaskOutboxStatus.NEW));

    awaiter.awaitProcessed(42L);

    verify(repository, atLeastOnce()).findStatus(42L);
  }

  @Test
  void returnsWhenOutboxRowCanNoLongerBeFound() {
    when(repository.findStatus(42L)).thenReturn(Optional.empty());

    awaiter.awaitProcessed(42L);

    verify(repository).findStatus(42L);
  }

  @Test
  void registersAwaitUntilAfterCommitWhenTransactionSynchronizationIsActive() {
    when(repository.findStatus(42L)).thenReturn(Optional.of(TaskOutboxStatus.PROCESSED));
    TransactionSynchronizationManager.initSynchronization();

    awaiter.awaitProcessedAfterCommit(42L);

    verify(repository, never()).findStatus(42L);

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(synchronization -> synchronization.afterCommit());

    verify(repository).findStatus(42L);
  }

  @Test
  void disabledAwaitDoesNotQueryRepository() {
    properties.getOutbox().getCompletion().setAwaitProcessed(false);

    awaiter.awaitProcessedAfterCommit(42L);

    verify(repository, never()).findStatus(42L);
  }
}
