package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxRecord;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TerminateTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskTerminationRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.SearchTaskRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskRequestContext;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchKey;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchOperator;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchParameterList;

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
    List<TaskOutboxRecord> records = repository.findPending(batchSize, retryPolicy.getMaxAttempts());
    int statusCode;

    for (TaskOutboxRecord record : records) {
      if (!repository.markProcessing(record.id())) {
        continue;
      }

      try {
        TaskAction action = TaskAction.fromId(record.action());

        TaskProcessor processor = switch (action) {
          case INITIATE -> this::createTask;
          case COMPLETE -> this::completeTask;
          case CANCEL -> this::cancelTask;
          case RECONFIGURE -> null;
        };

        if (processor == null) {
          log.warn("Task outbox {} unknown action {}", record.id(), record.action());
          statusCode = 500;
        } else {
          ResponseEntity<?> response = processor.process(record);
          statusCode = response != null ? response.getStatusCode().value() : 500;
          log.info("Task outbox {} response body {}", record.id(), response != null ? response.getBody() : null);
          if (isUnsuccessfulRequest(record, response, statusCode)) {
            continue;
          }
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
        handleFailure(record, ex.status(), ex.contentUTF8());
      } catch (IOException ex) {
        log.error("Task outbox {} payload could not be parsed", record.id(), ex);
        handleFailure(record, null, ex.getMessage());
      } catch (RuntimeException ex) {
        log.error("Task outbox {} create failed", record.id(), ex);
        handleFailure(record, null, ex.getMessage());
      }
    }
  }

  private boolean isUnsuccessfulRequest(TaskOutboxRecord record, ResponseEntity<?> response, int statusCode) {
    boolean requireBody = TaskAction.fromId(record.action()) == TaskAction.INITIATE;
    if (response != null && response.getStatusCode().is2xxSuccessful()) {
      if (!requireBody || response.getBody() != null) {
        return false;
      }
    }

    log.warn(
        "Task outbox {} create response missing body or unsuccessful with status {}",
        record.id(),
        statusCode
    );
    handleFailure(record, statusCode, "Task creation response missing task_id");
    return true;
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

    var searchRequest = SearchTaskRequest.builder()
        .searchParameters(
            List.of(
                TaskSearchParameterList.builder()
                    .key(TaskSearchKey.TASK_TYPE)
                    .operator(TaskSearchOperator.IN)
                    .values(terminateTaskOutboxPayload.taskTypes())
                    .build(),
                TaskSearchParameterList.builder()
                    .key(TaskSearchKey.CASE_ID)
                    .operator(TaskSearchOperator.IN)
                    .values(List.of(terminateTaskOutboxPayload.caseId()))
                    .build()
            )
        )
        .taskSortingParameters(null)
        .requestContext(TaskRequestContext.ALL_WORK)
        .build();

    var tasksToTerminate = taskManagementApiClient.searchTasks(searchRequest);

    if (!tasksToTerminate.getStatusCode().is2xxSuccessful() || tasksToTerminate.getBody() == null) {
      log.warn(
          "Failed to retrieve tasks to terminate for case {} and task types {} with action {}",
          terminateTaskOutboxPayload.caseId(),
          terminateTaskOutboxPayload.taskTypes(),
          action.getId()
      );
      return null;
    }

    TaskTerminationRequest request = TaskTerminationRequest.builder()
        .taskIds(tasksToTerminate.getBody().getTasks().stream().map(TaskPayload::getTaskId).toList())
        .action(action.getId())
        .build();

    return taskManagementApiClient.terminateTask(request);
  }

  private void handleFailure(TaskOutboxRecord record, Integer statusCode, String body) {
    int nextAttemptCount = record.attemptCount() + 1;
    LocalDateTime nextAttemptAt = retryPolicy.nextAttemptAt(nextAttemptCount, LocalDateTime.now());
    repository.markFailed(record.id(), statusCode, body, nextAttemptAt);
    if (nextAttemptAt == null) {
      log.warn(
          "Task outbox {} failed with status {}, retries exhausted",
          record.id(),
          statusCode
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
