package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxTrigger;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TerminateTaskOutboxPayload;

class TaskOutboxServiceTest {

  private final TaskOutboxRepository repository = mock(TaskOutboxRepository.class);
  private final TaskOutboxService service = new TaskOutboxService(
      repository,
      new ObjectMapper()
  );

  @Test
  void completeRequestsOnlyEnqueueOutboxRow() {
    LocalDateTime created = LocalDateTime.of(2026, 6, 11, 12, 0);
    TaskOutboxTrigger trigger = new TaskOutboxTrigger("123", "caseType", "complete-event", created);
    TerminateTaskOutboxPayload payload = new TerminateTaskOutboxPayload("123", "caseType", List.of("taskType"));
    when(repository.enqueueAndReturnId(
        eq("123"),
        eq("complete-event"),
        eq(created),
        anyString(),
        eq(TaskAction.COMPLETE.getId()),
        eq(null)
    )).thenReturn(42L);

    service.enqueueTaskCompleteRequest(trigger, payload);

    verify(repository).enqueueAndReturnId(
        eq("123"),
        eq("complete-event"),
        eq(created),
        anyString(),
        eq(TaskAction.COMPLETE.getId()),
        eq(null)
    );
  }

  @Test
  void cancelRequestsRemainAsynchronous() {
    LocalDateTime created = LocalDateTime.of(2026, 6, 11, 12, 0);
    TaskOutboxTrigger trigger = new TaskOutboxTrigger("123", "caseType", "event-id", created);
    TerminateTaskOutboxPayload payload = new TerminateTaskOutboxPayload("123", "caseType", List.of("taskType"));

    service.enqueueTaskCancelRequest(trigger, payload);

    verify(repository).enqueue(
        eq("123"),
        eq("event-id"),
        eq(created),
        anyString(),
        eq(TaskAction.CANCEL.getId())
    );
  }

  @Test
  void rejectsCancelRequestForDifferentCaseThanTrigger() {
    TaskOutboxTrigger trigger = TaskOutboxTrigger.create("123", "caseType", "event-id");
    TerminateTaskOutboxPayload payload = new TerminateTaskOutboxPayload("456", "caseType", List.of("taskType"));

    assertThatThrownBy(() -> service.enqueueTaskCancelRequest(trigger, payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("caseId must match the trigger");
  }

  @Test
  void rejectsBlankTriggerEventId() {
    TaskOutboxTrigger trigger = TaskOutboxTrigger.create("123", "caseType", " ");
    TerminateTaskOutboxPayload payload = new TerminateTaskOutboxPayload("123", "caseType", List.of("taskType"));

    assertThatThrownBy(() -> service.enqueueTaskCancelRequest(trigger, payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("trigger eventId must not be blank");
  }

  @Test
  void rejectsNullTriggerEventId() {
    TaskOutboxTrigger trigger = TaskOutboxTrigger.create("123", "caseType", null);
    TerminateTaskOutboxPayload payload = new TerminateTaskOutboxPayload("123", "caseType", List.of("taskType"));

    assertThatThrownBy(() -> service.enqueueTaskCancelRequest(trigger, payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("trigger eventId must not be blank");
  }
}
