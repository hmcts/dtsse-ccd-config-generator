package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class TaskOutboxFailureLogPollerTest {

  @Test
  void shouldOnlyRequestFailuresAfterLastLoggedHistoryId() {
    TaskOutboxRepository repository = mock(TaskOutboxRepository.class);
    TaskOutboxFailureLogPoller poller = new TaskOutboxFailureLogPoller(repository, 10);

    when(repository.findFailedHistoryAfter(0, 10)).thenReturn(List.of(
        new TaskOutboxFailureLogEntry(
            41,
            101,
            1234567890123456L,
            "initiate",
            "{\"task\":{}}",
            500,
            "first failure",
            LocalDateTime.now()
        ),
        new TaskOutboxFailureLogEntry(
            42,
            102,
            1234567890123456L,
            "complete",
            "{\"task_types\":[]}",
            500,
            "second failure",
            LocalDateTime.now()
        )
    ));
    when(repository.findFailedHistoryAfter(42, 10)).thenReturn(List.of());

    poller.poll();
    poller.poll();

    InOrder inOrder = Mockito.inOrder(repository);
    inOrder.verify(repository).findFailedHistoryAfter(0, 10);
    inOrder.verify(repository).findFailedHistoryAfter(42, 10);
  }

  @Test
  void shouldKeepCurrentHistoryIdWhenNoFailuresAreFound() {
    TaskOutboxRepository repository = mock(TaskOutboxRepository.class);
    TaskOutboxFailureLogPoller poller = new TaskOutboxFailureLogPoller(repository, 10);

    when(repository.findFailedHistoryAfter(0, 10)).thenReturn(List.of());

    poller.poll();
    poller.poll();

    verify(repository, Mockito.times(2)).findFailedHistoryAfter(0, 10);
  }
}
