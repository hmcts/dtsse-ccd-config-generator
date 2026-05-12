package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TerminateTaskOutboxPayload;

class TaskOutboxServiceTest {

  private final TaskOutboxRepository repository = mock(TaskOutboxRepository.class);
  private final TaskOutboxCompletionAwaiter completionAwaiter = mock(TaskOutboxCompletionAwaiter.class);
  private final TaskOutboxService service = new TaskOutboxService(
      repository,
      completionAwaiter,
      new ObjectMapper()
  );

  @Test
  void completeRequestsAwaitTheInsertedOutboxRow() {
    TerminateTaskOutboxPayload payload = new TerminateTaskOutboxPayload("123", "caseType", List.of("taskType"));
    when(repository.enqueueAndReturnId(eq("123"), anyString(), eq(TaskAction.COMPLETE.getId())))
        .thenReturn(42L);

    service.enqueueTaskCompleteRequest(payload);

    verify(completionAwaiter).awaitProcessedAfterCommit(42L);
  }

  @Test
  void cancelRequestsRemainAsynchronous() {
    TerminateTaskOutboxPayload payload = new TerminateTaskOutboxPayload("123", "caseType", List.of("taskType"));

    service.enqueueTaskCancelRequest(payload);

    verify(repository).enqueue(eq("123"), anyString(), eq(TaskAction.CANCEL.getId()));
    verifyNoInteractions(completionAwaiter);
  }
}
