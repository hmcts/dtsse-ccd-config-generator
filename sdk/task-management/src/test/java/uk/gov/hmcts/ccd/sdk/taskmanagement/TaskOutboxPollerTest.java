package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxRecord;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.GetTasksResponse;

class TaskOutboxPollerTest {

  private final TaskOutboxRepository repository = mock(TaskOutboxRepository.class);
  private final TaskManagementApiClient taskManagementApiClient = mock(TaskManagementApiClient.class);
  private final TaskOutboxRetryPolicy retryPolicy = new TaskOutboxRetryPolicy(new TaskManagementProperties());
  private final TaskOutboxPoller poller = new TaskOutboxPoller(
      repository,
      taskManagementApiClient,
      retryPolicy,
      5,
      new ObjectMapper()
  );

  @BeforeEach
  void setUp() {
    when(repository.markProcessing(1L)).thenReturn(true);
  }

  @Test
  void marksInitiateProcessedWhenTaskManagementReturnsSuccessfulEmptyResponse() {
    TaskOutboxRecord record = new TaskOutboxRecord(
        1L,
        """
            {
              "task": {
                "external_task_id": "task-1",
                "case_id": "1234567890123456"
              }
            }
            """,
        TaskAction.INITIATE.getId(),
        0
    );
    when(repository.findPending(5, 0)).thenReturn(List.of(record));
    when(taskManagementApiClient.createTask(any())).thenReturn(ResponseEntity.ok().build());

    poller.poll();

    verify(repository).markProcessed(1L, 200);
    verifyNoMoreInteractionsAfterProcessing();
  }

  @Test
  void marksReconfigureProcessedWhenTaskManagementReturnsSuccessfulEmptyResponse() {
    TaskOutboxRecord record = new TaskOutboxRecord(
        1L,
        """
            {
              "case_id": "1234567890123456",
              "case_type": "E2E",
              "tasks": []
            }
            """,
        TaskAction.RECONFIGURE.getId(),
        0
    );
    when(repository.findPending(5, 0)).thenReturn(List.of(record));
    when(taskManagementApiClient.reconfigureTask(any())).thenReturn(ResponseEntity.noContent().build());

    poller.poll();

    verify(repository).markProcessed(1L, 204);
    verifyNoMoreInteractionsAfterProcessing();
  }

  @Test
  void marksCompleteProcessedWhenNoTasksAreReturnedToTerminate() {
    TaskOutboxRecord record = new TaskOutboxRecord(
        1L,
        """
            {
              "case_id": "1234567890123456",
              "case_type": "E2E",
              "task_types": ["registerNewCase"]
            }
            """,
        TaskAction.COMPLETE.getId(),
        0
    );
    when(repository.findPending(5, 0)).thenReturn(List.of(record));
    when(taskManagementApiClient.getTasks(eq("1234567890123456"), eq(List.of("registerNewCase"))))
        .thenReturn(ResponseEntity.ok(GetTasksResponse.builder().tasks(List.of()).build()));

    poller.poll();

    verify(repository).markProcessed(1L, 200);
    verifyNoMoreInteractionsAfterProcessing();
  }

  @Test
  void marksCompleteProcessedWhenTaskSearchReturnsSuccessfulEmptyResponse() {
    TaskOutboxRecord record = new TaskOutboxRecord(
        1L,
        """
            {
              "case_id": "1234567890123456",
              "case_type": "E2E",
              "task_types": ["registerNewCase"]
            }
            """,
        TaskAction.COMPLETE.getId(),
        0
    );
    when(repository.findPending(5, 0)).thenReturn(List.of(record));
    when(taskManagementApiClient.getTasks(eq("1234567890123456"), eq(List.of("registerNewCase"))))
        .thenReturn(ResponseEntity.ok().build());

    poller.poll();

    verify(repository).markProcessed(1L, 200);
    verifyNoMoreInteractionsAfterProcessing();
  }

  private void verifyNoMoreInteractionsAfterProcessing() {
    verify(repository).findPending(5, 0);
    verify(repository).markProcessing(1L);
    verifyNoMoreInteractions(repository);
  }
}
