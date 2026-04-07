package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
public class TaskOutboxPoller {
  @FunctionalInterface
  private interface TaskProcessor {
    ResponseEntity<?> process(TaskOutboxRecord record) throws IOException;
  }

  private final TaskOutboxRepository repository;
  private final TaskManagementApiClient taskManagementApiClient;
  private final TaskOutboxRetryPolicy retryPolicy;
  private final TaskOutboxTelemetry telemetry;
  private final int batchSize;
  private final ObjectMapper objectMapper;

  public TaskOutboxPoller(
      TaskOutboxRepository repository,
      TaskManagementApiClient taskManagementApiClient,
      TaskOutboxRetryPolicy retryPolicy,
      TaskOutboxTelemetry telemetry,
      int batchSize,
      ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.taskManagementApiClient = taskManagementApiClient;
    this.retryPolicy = retryPolicy;
    this.telemetry = telemetry;
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
        if (isUnsuccessfulRequest(record, response)) {
          continue;
        }

        repository.markProcessed(record.id(), statusCode);
        log.info("Task outbox {} processed with status {}", record.id(), statusCode);
      } catch (FeignException ex) {
        log.warn(
            "Task outbox {} create failed with status {}: {}",
            record.id(),
            ex.status(),
            ex.contentUTF8(),
            ex
        );
        handleFailure(record, ex.status(), ex.contentUTF8(), maxAttempts);
      } catch (IOException ex) {
        log.error("Task outbox {} payload could not be parsed", record.id(), ex);
        handleFailure(record, null, ex.getMessage(), maxAttempts);
      } catch (RuntimeException ex) {
        log.error("Task outbox {} create failed", record.id(), ex);
        handleFailure(record, null, ex.getMessage(), maxAttempts);
      }
    }
  }

  private boolean isUnsuccessfulRequest(TaskOutboxRecord record, ResponseEntity<?> response) {
    TaskAction action = TaskAction.fromId(record.requestedAction());
    if (response == null) {
      log.warn("Task outbox {} received null response for action {}", record.id(), action.getId());
      handleFailure(record, null, "Task outbox received null response", retryPolicy.getMaxAttempts());
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
          retryPolicy.getMaxAttempts()
      );
      return true;
    }

    if (List.of(TaskAction.INITIATE, TaskAction.RECONFIGURE).contains(action) && response.getBody() == null) {
      log.warn("Task outbox {} create response missing body", record.id());
      handleFailure(
          record,
          response.getStatusCode().value(),
          "Task creation response missing task_id",
          retryPolicy.getMaxAttempts()
      );
      return true;
    }

    return false;
  }

  private ResponseEntity<?> createTask(TaskOutboxRecord record) throws IOException {
    TaskCreateRequest request = objectMapper.readValue(record.payload(), TaskCreateRequest.class);
    log.warn("Task outbox {} sending payload {}", record.id(), objectMapper.writeValueAsString(request));
    return taskManagementApiClient.createTask(request);
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
    List<String> taskTypes = terminateTaskOutboxPayload.taskTypes();

    var tasksToTerminate = taskManagementApiClient.getTasks(caseId, taskTypes);
    GetTasksResponse responseBody = tasksToTerminate.getBody();

    String actionId = action.getId();
    if (!tasksToTerminate.getStatusCode().is2xxSuccessful() || responseBody == null) {
      log.warn("Failed to retrieve tasks to terminate for case {} and task types {} with action {}",
            caseId, taskTypes, actionId);
      return null;
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
        .tasks(taskReconfigurePayloads)
        .build();

    return taskManagementApiClient.reconfigureTask(request);
  }

  private void handleFailure(TaskOutboxRecord record, Integer statusCode, String body, int maxAttempts) {
    int nextAttemptCount = record.attemptCount() + 1;
    LocalDateTime nextAttemptAt = retryPolicy.nextAttemptAt(nextAttemptCount, LocalDateTime.now());
    repository.markFailed(record.id(), statusCode, body, nextAttemptAt);
    if (nextAttemptAt == null) {
      Long nextRetryableOutboxId = repository.findNextRetryableInCase(record.caseId(), record.id(), maxAttempts);
      log.warn(
          "Task outbox {} failed with status {}, retries exhausted; next retryable record for case {} is {}",
          record.id(),
          statusCode,
          record.caseId(),
          nextRetryableOutboxId
      );
      telemetry.retriesExhausted(
          new TaskOutboxRetriesExhaustedEvent(record, statusCode, maxAttempts, nextRetryableOutboxId)
      );
    } else {
      log.warn(
          "Task outbox {} failed with status {}, retrying at {}",
          record.id(),
          statusCode,
          nextAttemptAt
      );
    }
  }
}
