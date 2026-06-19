package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.CollectionUtils;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskReconfigurePayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.ReconfigureTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxRecord;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TerminateTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskReconfigureRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskTerminationRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.GetTasksResponse;

@Slf4j
public class TaskOutboxPoller {
  @FunctionalInterface
  private interface TaskProcessor {
    ResponseEntity<?> process(TaskOutboxRecord record) throws IOException;
  }

  private final TaskOutboxRepository repository;
  private final TaskManagementApiClient taskManagementApiClient;
  private final TaskOutboxRetryPolicy retryPolicy;
  private final int batchSize;
  private final ObjectMapper objectMapper;

  public TaskOutboxPoller(
      TaskOutboxRepository repository,
      TaskManagementApiClient taskManagementApiClient,
      TaskOutboxRetryPolicy retryPolicy,
      int batchSize,
      ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.taskManagementApiClient = taskManagementApiClient;
    this.retryPolicy = retryPolicy;
    this.batchSize = batchSize;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedDelayString = "${task-management.outbox.poller.delay:1000}")
  public void poll() {
    int maxAttempts = retryPolicy.getMaxAttempts();
    List<TaskOutboxRecord> records = repository.claimPending(batchSize, maxAttempts);
    int statusCode;

    for (TaskOutboxRecord record : records) {
      try {
        TaskAction action = TaskAction.fromId(record.requestedAction());

        TaskProcessor processor = switch (action) {
          case INITIATE -> this::createTask;
          case COMPLETE -> this::completeTask;
          case CANCEL -> this::cancelTask;
          case RECONFIGURE -> this::reconfigureTask;
        };

        ResponseEntity<?> response = processor.process(record);
        statusCode = response != null ? response.getStatusCode().value() : 500;
        log.info("Task outbox {} response body {}", record.id(), response != null ? response.getBody() : null);
        if (isUnsuccessfulRequest(record, response, maxAttempts)) {
          continue;
        }

        if (!repository.markProcessed(record.id(), record.claimToken(), statusCode)) {
          logLostLease(record, "PROCESSED");
          continue;
        }
        log.info(
            "TaskOutboxOutcome status=PROCESSED taskOutboxId={} caseId={} eventId={} triggerCreated={} "
                + "requestedAction={} attemptCount={} responseCode={}",
            record.id(),
            record.caseId(),
            record.eventId(),
            record.created(),
            record.requestedAction(),
            record.attemptCount(),
            statusCode
        );
      } catch (FeignException ex) {
        log.warn(
            "Task outbox {} create failed with status {}: {}",
            record.id(),
            ex.status(),
            ex.contentUTF8(),
            ex
        );
        handleFailure(
            record,
            ex.status(),
            ex.contentUTF8(),
            maxAttempts,
            TaskOutboxFailureClassifier.isRecoverable(ex.status())
        );
      } catch (IOException ex) {
        log.error("Task outbox {} payload could not be parsed", record.id(), ex);
        handleFailure(record, null, ex.getMessage(), maxAttempts, false);
      } catch (IllegalArgumentException ex) {
        log.error("Task outbox {} contains invalid action or request data", record.id(), ex);
        handleFailure(record, null, ex.getMessage(), maxAttempts, false);
      } catch (RuntimeException ex) {
        log.error("Task outbox {} create failed", record.id(), ex);
        handleFailure(record, null, ex.getMessage(), maxAttempts, true);
      }
    }
  }

  private boolean isUnsuccessfulRequest(
      TaskOutboxRecord record,
      ResponseEntity<?> response,
      int maxAttempts
  ) {
    TaskAction action = TaskAction.fromId(record.requestedAction());
    if (response == null) {
      log.warn("Task outbox {} received null response for action {}", record.id(), action.getId());
      handleFailure(record, null, "Task outbox received null response", maxAttempts, true);
      return true;
    }

    if (!response.getStatusCode().is2xxSuccessful()) {
      log.warn(
          "Task outbox {} response unsuccessful for action {} with status {}",
          record.id(),
          action.getId(),
          response.getStatusCode().value()
      );
      handleFailure(
          record,
          response.getStatusCode().value(),
          "Task outbox response unsuccessful",
          maxAttempts,
          TaskOutboxFailureClassifier.isRecoverable(response.getStatusCode().value())
      );
      return true;
    }

    if (action == TaskAction.INITIATE
        && response.getBody() == null
        && response.getStatusCode().value() != 204) {
      log.warn("Task outbox {} create response missing body", record.id());
      handleFailure(
          record,
          response.getStatusCode().value(),
          "Task creation response missing task_id",
          maxAttempts,
          true
      );
      return true;
    }

    if (action == TaskAction.RECONFIGURE && response.getBody() == null) {
      log.warn("Task outbox {} reconfigure response missing body", record.id());
      handleFailure(
          record,
          response.getStatusCode().value(),
          "Task reconfiguration response missing body",
          maxAttempts,
          true
      );
      return true;
    }

    return false;
  }

  private ResponseEntity<?> createTask(TaskOutboxRecord record) throws IOException {
    TaskCreateRequest request = objectMapper.readValue(record.payload(), TaskCreateRequest.class);
    List<TaskPayload> tasks = request.tasks();
    if (CollectionUtils.isEmpty(tasks)) {
      throw new IllegalArgumentException("Task create request must contain at least one task");
    }

    ResponseEntity<?> lastResponse = null;
    for (TaskPayload task : tasks) {
      log.warn("Task outbox {} sending create payload {}", record.id(), objectMapper.writeValueAsString(task));
      ResponseEntity<?> response = taskManagementApiClient.createTask(task);
      if (response == null
          || !response.getStatusCode().is2xxSuccessful()
          || (response.getBody() == null && response.getStatusCode().value() != 204)) {
        return response;
      }
      lastResponse = response;
    }
    return lastResponse;
  }

  private ResponseEntity<?> cancelTask(TaskOutboxRecord record) throws IOException {
    return terminateTask(record, TaskAction.CANCEL);
  }

  private ResponseEntity<?> completeTask(TaskOutboxRecord record) throws IOException {
    return terminateTask(record, TaskAction.COMPLETE);
  }

  private ResponseEntity<?> terminateTask(TaskOutboxRecord record, TaskAction action) throws IOException {
    TerminateTaskOutboxPayload terminateTaskOutboxPayload =
        objectMapper.readValue(record.payload(), TerminateTaskOutboxPayload.class);
    String caseId = terminateTaskOutboxPayload.caseId();
    String caseType = terminateTaskOutboxPayload.caseType();
    List<String> taskTypes = terminateTaskOutboxPayload.taskTypes();

    var tasksToTerminate = taskManagementApiClient.getTasks(caseId, caseType, taskTypes);
    GetTasksResponse responseBody = tasksToTerminate.getBody();

    String actionId = action.getId();
    if (!tasksToTerminate.getStatusCode().is2xxSuccessful() || responseBody == null) {
      log.warn("Failed to retrieve tasks to terminate for case {} and task types {} with action {}",
            caseId, taskTypes, actionId);
      return tasksToTerminate.getStatusCode().is2xxSuccessful() ? null : tasksToTerminate;
    }

    List<TaskPayload> tasks = responseBody.getTasks();
    if (CollectionUtils.isEmpty(tasks)) {
      log.debug("There are no tasks to terminate for case {} and task types {} with action {}",
          caseId, taskTypes, actionId);
      // This is a slight bodge, but it signals to the caller this is not a failure scenario.
      return ResponseEntity.ok().build();
    }

    TaskTerminationRequest request = TaskTerminationRequest.builder()
        .taskIds(tasks.stream().map(TaskPayload::getId).toList())
        .action(actionId)
        .caseTypeId(caseType)
        .build();

    return taskManagementApiClient.terminateTask(request);
  }

  private ResponseEntity<?> reconfigureTask(TaskOutboxRecord record) throws IOException {
    ReconfigureTaskOutboxPayload reconfigurePayload =
        objectMapper.readValue(record.payload(), ReconfigureTaskOutboxPayload.class);

    List<TaskReconfigurePayload> taskReconfigurePayloads = reconfigurePayload.tasks() == null
        ? List.of()
        : reconfigurePayload.tasks().stream()
            .map(task -> TaskReconfigurePayload.builder()
                .id(task.getId())
                .title(task.getTaskTitle())
                .caseCategory(task.getCaseCategory())
                .caseName(task.getCaseName())
                .region(task.getRegion())
                .location(task.getLocation())
                .workType(task.getWorkTypeId())
                .roleCategory(task.getRoleCategory())
                .description(task.getDescription())
                .dueDateTime(task.getDueDate())
                .priorityDate(task.getPriorityDate())
                .majorPriority(task.getMajorPriority())
                .minorPriority(task.getMinorPriority())
                .locationName(task.getLocationName())
                .additionalProperties(task.getAdditionalProperties())
                .permissions(task.getPermissions())
                .build())
            .toList();

    TaskReconfigureRequest request = TaskReconfigureRequest.builder()
        .caseTypeId(reconfigurePayload.caseType())
        .tasks(taskReconfigurePayloads)
        .build();

    return taskManagementApiClient.reconfigureTask(request);
  }

  private void handleFailure(
      TaskOutboxRecord record,
      Integer statusCode,
      String body,
      int maxAttempts,
      boolean recoverable
  ) {
    LocalDateTime nextAttemptAt = recoverable
        ? retryPolicy.nextAttemptAt(record.attemptCount(), LocalDateTime.now(ZoneOffset.UTC))
        : null;
    boolean retryScheduled = nextAttemptAt != null;
    String outcome;
    boolean updated;

    if (retryScheduled) {
      updated = repository.rescheduleAfterFailure(
          record.id(),
          record.claimToken(),
          statusCode,
          body,
          nextAttemptAt
      );
      outcome = "PENDING";
    } else {
      updated = repository.markUnprocessable(record.id(), record.claimToken(), statusCode, body);
      outcome = "UNPROCESSABLE";
    }

    if (!updated) {
      logLostLease(record, outcome);
      return;
    }

    log.warn(
        "TaskOutboxOutcome status={} taskOutboxId={} caseId={} eventId={} triggerCreated={} "
            + "requestedAction={} attemptCount={} maxAttempts={} responseCode={} nextAttemptAt={} "
            + "recoverable={} retryScheduled={} error={} payload={}",
        outcome,
        record.id(),
        record.caseId(),
        record.eventId(),
        record.created(),
        record.requestedAction(),
        record.attemptCount(),
        maxAttempts,
        statusCode,
        nextAttemptAt,
        recoverable,
        retryScheduled,
        body,
        record.payload()
    );
    if (!retryScheduled) {
      log.warn(
          "TaskOutboxCaseBlocked caseId={} eventId={} triggerCreated={} taskOutboxId={} "
              + "requestedAction={} attemptCount={} maxAttempts={} lastStatusCode={} recoverable={} eventType={}",
          record.caseId(),
          record.eventId(),
          record.created(),
          record.id(),
          record.requestedAction(),
          record.attemptCount(),
          maxAttempts,
          statusCode,
          recoverable,
          "task-outbox-unprocessable"
      );
    }
  }

  private void logLostLease(TaskOutboxRecord record, String attemptedOutcome) {
    log.warn(
        "Task outbox {} discarded {} outcome because processing lease {} is no longer current",
        record.id(),
        attemptedOutcome,
        record.claimToken()
    );
  }
}
