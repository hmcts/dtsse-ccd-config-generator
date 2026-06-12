package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxRecord;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskCreateResponse;

class TaskOutboxPollerTest {

  private static final long OUTBOX_ID = 42L;

  private final TaskOutboxRepository repository = mock(TaskOutboxRepository.class);
  private final TaskManagementApiClient apiClient = mock(TaskManagementApiClient.class);
  private final TaskManagementProperties properties = new TaskManagementProperties();
  private final TaskOutboxRetryPolicy retryPolicy = new TaskOutboxRetryPolicy(properties);
  private final TaskOutboxPoller poller = new TaskOutboxPoller(
      repository,
      apiClient,
      retryPolicy,
      5,
      new ObjectMapper()
  );

  @BeforeEach
  void setUp() {
    when(repository.claimPending(5, 9)).thenReturn(List.of(record(0, validPayload(), "initiate")));
  }

  @Test
  void marksPermanentClientFailureUnprocessableWithoutRetry() {
    when(apiClient.createTask(any())).thenReturn(response(HttpStatus.BAD_REQUEST, null));

    poller.poll();

    verify(repository).markUnprocessable(
        OUTBOX_ID,
        HttpStatus.BAD_REQUEST.value(),
        "Task outbox response unsuccessful"
    );
    verify(repository, never()).markFailed(eq(OUTBOX_ID), any(), any(), any());
  }

  @Test
  void schedulesRecoverableServerFailureWithExponentialBackoff() {
    when(apiClient.createTask(any())).thenReturn(response(HttpStatus.SERVICE_UNAVAILABLE, null));
    ArgumentCaptor<LocalDateTime> nextAttemptAt = ArgumentCaptor.forClass(LocalDateTime.class);

    poller.poll();

    verify(repository).markFailed(
        eq(OUTBOX_ID),
        eq(HttpStatus.SERVICE_UNAVAILABLE.value()),
        eq("Task outbox response unsuccessful"),
        nextAttemptAt.capture()
    );
    verify(repository, never()).markUnprocessable(eq(OUTBOX_ID), any(), any());
  }

  @Test
  void schedulesConflictForRetry() {
    when(apiClient.createTask(any())).thenReturn(response(HttpStatus.CONFLICT, null));

    poller.poll();

    verify(repository).markFailed(
        eq(OUTBOX_ID),
        eq(HttpStatus.CONFLICT.value()),
        eq("Task outbox response unsuccessful"),
        any(LocalDateTime.class)
    );
  }

  @Test
  void marksRecoverableFailureUnprocessableAfterEightRetries() {
    when(repository.claimPending(5, 9)).thenReturn(List.of(record(8, validPayload(), "initiate")));
    when(apiClient.createTask(any())).thenReturn(response(HttpStatus.SERVICE_UNAVAILABLE, null));

    poller.poll();

    verify(repository).markUnprocessable(
        OUTBOX_ID,
        HttpStatus.SERVICE_UNAVAILABLE.value(),
        "Task outbox response unsuccessful"
    );
    verify(repository, never()).markFailed(eq(OUTBOX_ID), any(), any(), any());
  }

  @Test
  void marksMalformedStoredPayloadUnprocessable() {
    when(repository.claimPending(5, 9)).thenReturn(List.of(record(0, "{", "initiate")));

    poller.poll();

    verify(repository).markUnprocessable(eq(OUTBOX_ID), eq(null), any());
    verify(apiClient, never()).createTask(any());
  }

  @Test
  void retriesAmbiguousRuntimeFailure() {
    when(apiClient.createTask(any())).thenThrow(new IllegalStateException("temporary local failure"));

    poller.poll();

    verify(repository).markFailed(
        eq(OUTBOX_ID),
        eq(null),
        eq("temporary local failure"),
        any(LocalDateTime.class)
    );
  }

  @Test
  void marksSuccessfulResponseProcessed() {
    TaskCreateResponse body = mock(TaskCreateResponse.class);
    when(apiClient.createTask(any())).thenReturn(response(HttpStatus.CREATED, body));

    poller.poll();

    verify(repository).markProcessed(OUTBOX_ID, HttpStatus.CREATED.value());
  }

  private TaskOutboxRecord record(int attemptCount, String payload, String action) {
    return new TaskOutboxRecord(
        OUTBOX_ID,
        1234567890123456L,
        "event-id",
        LocalDateTime.of(2026, 6, 12, 10, 0),
        payload,
        action,
        attemptCount
    );
  }

  private String validPayload() {
    return """
        {
          "task": {
            "external_task_id": "external-task-id"
          }
        }
        """;
  }

  private ResponseEntity<TaskCreateResponse> response(HttpStatus status, TaskCreateResponse body) {
    return ResponseEntity.status(status).body(body);
  }
}
