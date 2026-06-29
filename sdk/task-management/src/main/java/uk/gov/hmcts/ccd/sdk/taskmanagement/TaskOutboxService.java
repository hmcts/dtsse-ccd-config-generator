package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.ReconfigureTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxTrigger;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TerminateTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;

public class TaskOutboxService {

  private final TaskOutboxRepository repository;
  private final ObjectMapper objectMapper;

  public TaskOutboxService(
      TaskOutboxRepository repository,
      ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  public void enqueueTaskCreateRequest(TaskOutboxTrigger trigger, TaskCreateRequest request) {
    enqueueTaskCreateRequest(trigger, request, null);
  }

  public void enqueueTaskCreateRequest(
      TaskOutboxTrigger trigger,
      TaskCreateRequest request,
      LocalDateTime nextAttemptAt
  ) {
    validateTrigger(trigger);
    Objects.requireNonNull(request, "request must not be null");
    List<TaskPayload> tasks = requireNonEmpty(request.tasks(), "tasks");
    validateCreateTasks(trigger, tasks);

    try {
      String payload = objectMapper.writeValueAsString(request);
      repository.enqueue(
          trigger.caseId(),
          trigger.eventId(),
          trigger.created(),
          payload,
          TaskAction.INITIATE.getId(),
          nextAttemptAt
      );
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to enqueue task outbox entry", ex);
    }
  }

  private void validateCreateTasks(TaskOutboxTrigger trigger, List<TaskPayload> tasks) {
    Set<String> idempotencyKeys = new HashSet<>();

    for (TaskPayload task : tasks) {
      Objects.requireNonNull(task, "task must not be null");
      requireText(task.getExternalTaskId(), "external task id");
      requireText(task.getCaseId(), "caseId");
      requireText(task.getCaseTypeId(), "caseTypeId");
      requireMatchingTriggerValue(trigger.caseId(), task.getCaseId(), "caseId");
      requireMatchingTriggerValue(trigger.caseType(), task.getCaseTypeId(), "caseTypeId");

      String idempotencyKey = task.getExternalTaskId() + "\u0000" + task.getCaseTypeId();
      if (!idempotencyKeys.add(idempotencyKey)) {
        throw new IllegalArgumentException("tasks must not contain duplicate external task id and caseTypeId");
      }
    }
  }

  public void enqueueTaskCompleteRequest(TaskOutboxTrigger trigger, TerminateTaskOutboxPayload payload) {
    validateTrigger(trigger);
    Objects.requireNonNull(payload, "payload must not be null");
    requireText(payload.caseId(), "caseId");
    requireText(payload.caseType(), "caseType");
    requireNonEmpty(payload.taskTypes(), "taskTypes");
    requireMatchingTriggerValue(trigger.caseId(), payload.caseId(), "caseId");
    requireMatchingTriggerValue(trigger.caseType(), payload.caseType(), "caseType");

    try {
      repository.enqueueAndReturnId(
          trigger.caseId(),
          trigger.eventId(),
          trigger.created(),
          objectMapper.writeValueAsString(payload),
          TaskAction.COMPLETE.getId(),
          null
      );
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to enqueue task outbox entry", ex);
    }
  }

  public void enqueueTaskCancelRequest(TaskOutboxTrigger trigger, TerminateTaskOutboxPayload payload) {
    validateTrigger(trigger);
    Objects.requireNonNull(payload, "payload must not be null");
    requireText(payload.caseId(), "caseId");
    requireText(payload.caseType(), "caseType");
    requireNonEmpty(payload.taskTypes(), "taskTypes");
    requireMatchingTriggerValue(trigger.caseId(), payload.caseId(), "caseId");
    requireMatchingTriggerValue(trigger.caseType(), payload.caseType(), "caseType");

    try {
      repository.enqueue(
          trigger.caseId(),
          trigger.eventId(),
          trigger.created(),
          objectMapper.writeValueAsString(payload),
          TaskAction.CANCEL.getId()
      );
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to enqueue task outbox entry", ex);
    }
  }

  public void enqueueTaskReconfigureRequest(TaskOutboxTrigger trigger, ReconfigureTaskOutboxPayload payload) {
    validateTrigger(trigger);
    Objects.requireNonNull(payload, "payload must not be null");
    requireText(payload.caseId(), "caseId");
    requireText(payload.caseType(), "caseType");
    requireMatchingTriggerValue(trigger.caseId(), payload.caseId(), "caseId");
    requireMatchingTriggerValue(trigger.caseType(), payload.caseType(), "caseType");

    try {
      repository.enqueue(
          trigger.caseId(),
          trigger.eventId(),
          trigger.created(),
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

  private void validateTrigger(TaskOutboxTrigger trigger) {
    Objects.requireNonNull(trigger, "trigger must not be null");
    requireText(trigger.caseId(), "trigger caseId");
    requireText(trigger.caseType(), "trigger caseType");
    requireText(trigger.eventId(), "trigger eventId");
    Objects.requireNonNull(trigger.created(), "trigger created must not be null");
  }

  private void requireMatchingTriggerValue(String triggerValue, String payloadValue, String field) {
    if (!triggerValue.equals(payloadValue)) {
      throw new IllegalArgumentException(field + " must match the trigger");
    }
  }

  private <T> List<T> requireNonEmpty(List<T> value, String field) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be empty");
    }
    return value;
  }
}
