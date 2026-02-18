package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.ReconfigureTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TerminateTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;

public class TaskOutboxService {

  private final TaskOutboxRepository repository;
  private final ObjectMapper objectMapper;

  public TaskOutboxService(TaskOutboxRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  public void enqueueTaskCreateRequest(TaskCreateRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    TaskPayload task = Objects.requireNonNull(request.task(), "task must not be null");
    requireText(task.getExternalTaskId(), "external task id");
    requireText(task.getCaseId(), "caseId");
    requireText(task.getCaseTypeId(), "caseTypeId");

    try {
      String payload = objectMapper.writeValueAsString(request);
      repository.enqueue(task.getCaseId(), task.getCaseTypeId(), payload, TaskAction.INITIATE.getId());
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to enqueue task outbox entry", ex);
    }
  }

  public void enqueueTaskCompleteRequest(TerminateTaskOutboxPayload payload) {
    Objects.requireNonNull(payload, "payload must not be null");
    requireText(payload.caseId(), "caseId");

    try {
      repository.enqueue(
          payload.caseId(),
          payload.caseType(),
          objectMapper.writeValueAsString(payload),
          TaskAction.COMPLETE.getId()
      );
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to enqueue task outbox entry", ex);
    }
  }

  public void enqueueTaskCancelRequest(TerminateTaskOutboxPayload payload) {
    Objects.requireNonNull(payload, "payload must not be null");
    requireText(payload.caseId(), "caseId");

    try {
      repository.enqueue(
          payload.caseId(),
          payload.caseType(),
          objectMapper.writeValueAsString(payload),
          TaskAction.CANCEL.getId()
      );
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to enqueue task outbox entry", ex);
    }
  }

  public void enqueueTaskReconfigureRequest(ReconfigureTaskOutboxPayload payload) {
    Objects.requireNonNull(payload, "payload must not be null");
    requireText(payload.caseId(), "caseId");

    try {
      repository.enqueue(
          payload.caseId(),
          payload.caseType(),
          objectMapper.writeValueAsString(payload),
          TaskAction.RECONFIGURE.getId()
      );
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to enqueue task outbox entry", ex);
    }
  }

  private void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
