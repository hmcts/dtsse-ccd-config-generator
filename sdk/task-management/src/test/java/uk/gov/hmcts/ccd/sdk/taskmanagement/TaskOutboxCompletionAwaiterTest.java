package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxStatus;

class TaskOutboxCompletionAwaiterTest {

  private final TaskOutboxRepository repository = mock(TaskOutboxRepository.class);
  private final DataSource dataSource = mock(DataSource.class);
  private final TaskManagementProperties properties = new TaskManagementProperties();
  private final TaskOutboxCompletionAwaiter awaiter = new TaskOutboxCompletionAwaiter(
      repository,
      properties,
      dataSource,
      new ObjectMapper()
  );

  @Test
  void returnsImmediatelyWhenCompleteRowAlreadyFinishedAfterListen() throws Exception {
    ListeningConnection listeningConnection = listeningConnection();
    TaskOutboxCompletionResult result = processedResult();
    when(repository.findFinishedCompleteTask(42L)).thenReturn(Optional.of(result));

    TaskOutboxCompletionResult actual = awaiter.awaitCompleteFinished(42L);

    assertThat(actual).isEqualTo(result);
    verify(listeningConnection.statement).execute("LISTEN task_outbox_complete_finished");
    verify(listeningConnection.pgConnection, never()).getNotifications(100);
  }

  @Test
  void waitsForMatchingNotificationThenReturnsAuthoritativeRowState() throws Exception {
    ListeningConnection listeningConnection = listeningConnection();
    TaskOutboxCompletionResult result = processedResult();
    when(repository.findFinishedCompleteTask(42L))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(result));
    PGNotification notification = notification(42L, "PROCESSED");
    when(listeningConnection.pgConnection.getNotifications(100)).thenReturn(new PGNotification[] {notification});

    TaskOutboxCompletionResult actual = awaiter.awaitCompleteFinished(42L);

    assertThat(actual).isEqualTo(result);
    verify(repository, times(2)).findFinishedCompleteTask(42L);
  }

  @Test
  void ignoresNotificationsForOtherRows() throws Exception {
    properties.getOutbox().getCompletion().setTimeout(Duration.ofMillis(1));
    properties.getOutbox().getCompletion().setPollInterval(Duration.ofMillis(1));
    ListeningConnection listeningConnection = listeningConnection();
    when(repository.findFinishedCompleteTask(42L)).thenReturn(Optional.empty());
    PGNotification notification = notification(43L, "PROCESSED");
    when(listeningConnection.pgConnection.getNotifications(1)).thenReturn(new PGNotification[] {notification});

    assertThatThrownBy(() -> awaiter.awaitCompleteFinished(42L))
        .isInstanceOf(TaskOutboxTimeoutException.class)
        .hasMessageContaining("task_outbox id 42");
  }

  @Test
  void awaitsCompletionRowsCreatedAfterMarker() throws Exception {
    final ListeningConnection listeningConnection = listeningConnection();
    when(repository.findCompletionIdsForCaseAfter(123L, 41L)).thenReturn(List.of(42L));
    when(repository.findFinishedCompleteTask(42L)).thenReturn(Optional.of(processedResult()));

    awaiter.awaitCompletionsCreatedAfter(123L, 41L);

    verify(repository).findCompletionIdsForCaseAfter(123L, 41L);
    verify(repository).findFinishedCompleteTask(42L);
    verify(listeningConnection.statement).execute("LISTEN task_outbox_complete_finished");
  }

  @Test
  void disabledAwaitDoesNotSearchForCompletionRows() {
    properties.getOutbox().getCompletion().setAwaitProcessed(false);

    awaiter.awaitCompletionsCreatedAfter(123L, 41L);

    verify(repository, times(0)).findCompletionIdsForCaseAfter(123L, 41L);
    verify(repository, times(0)).findFinishedCompleteTask(42L);
  }

  @Test
  void throwsWhenListenConnectionCannotBeOpened() throws Exception {
    when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("unavailable"));

    assertThatThrownBy(() -> awaiter.awaitCompleteFinished(42L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unable to listen");
  }

  private ListeningConnection listeningConnection() throws Exception {
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    PGConnection pgConnection = mock(PGConnection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    return new ListeningConnection(statement, pgConnection);
  }

  private PGNotification notification(long id, String status) {
    PGNotification notification = mock(PGNotification.class);
    when(notification.getName()).thenReturn("task_outbox_complete_finished");
    when(notification.getParameter()).thenReturn("""
        {
          "id": %d,
          "case_id": 123,
          "requested_action": "complete",
          "old_status": "PROCESSING",
          "new_status": "%s",
          "attempt_count": 1,
          "updated": "2026-06-04T10:00:00"
        }
        """.formatted(id, status));
    return notification;
  }

  private TaskOutboxCompletionResult processedResult() {
    return new TaskOutboxCompletionResult(
        42L,
        123L,
        "complete",
        TaskOutboxStatus.PROCESSED,
        1
    );
  }

  private record ListeningConnection(Statement statement, PGConnection pgConnection) {}
}
