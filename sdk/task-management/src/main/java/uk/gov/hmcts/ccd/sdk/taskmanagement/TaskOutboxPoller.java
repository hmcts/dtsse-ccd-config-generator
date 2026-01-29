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
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.CancelTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.CompleteTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxRecord;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskTerminationRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskCreateResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskTerminationResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.SearchTaskRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskRequestContext;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchKey;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchParameterList;

import static uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction.CANCEL;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction.COMPLETE;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction.INITIATE;

@Slf4j
public class TaskOutboxPoller {

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

        if (action == INITIATE) {
          ResponseEntity<TaskCreateResponse> response = createTask(record);
          statusCode = response.getStatusCode().value();
          log.warn("Task outbox {} create response body {}", record.id(), response.getBody());
          if (isUnsuccessfulRequest(record, response, statusCode)) continue;
        } else if (action == COMPLETE) {
          ResponseEntity<TaskTerminationResponse> response = completeTask(record);
          statusCode = response != null ? response.getStatusCode().value() : 500;
          if (isUnsuccessfulRequest(record, response, statusCode)) continue;
        } else if (action == CANCEL) {
          ResponseEntity<TaskTerminationResponse> response = cancelTask(record);
          statusCode = response != null ? response.getStatusCode().value() : 500;
          if (isUnsuccessfulRequest(record, response, statusCode)) continue;
        } else {
          log.warn("Task outbox {} unknown action {}", record.id(), record.action());
          statusCode = 500;
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
    // HTTP 204 No Content is a valid success response for idempotent task creation
    // (task already exists with the same external_task_id and case_type_id)
    if (response != null && statusCode == 204) {
      log.info("Task outbox {} received 204 No Content - task already exists (idempotent success)", record.id());
      return false;
    }

    if (response != null && response.getBody() != null && response.getStatusCode().is2xxSuccessful()) {
      return false;
    }

    log.warn(
      "Task outbox {} create response missing body or unsuccessful with status {}",
      record.id(),
      statusCode
    );
    handleFailure(record, statusCode, "Task creation response missing task_id");
    return true;
  }

  private ResponseEntity<TaskCreateResponse> createTask(TaskOutboxRecord record) throws IOException {
    TaskCreateRequest request = objectMapper.readValue(record.payload(), TaskCreateRequest.class);
    log.warn("Task outbox {} sending payload {}", record.id(), objectMapper.writeValueAsString(request));
    return taskManagementApiClient.createTask(request);
  }

  private ResponseEntity<TaskTerminationResponse> completeTask(TaskOutboxRecord record) throws IOException {
    CompleteTaskOutboxPayload completeTaskOutboxPayload = objectMapper.readValue(record.payload(), CompleteTaskOutboxPayload.class);

    var searchRequest = SearchTaskRequest.builder().searchParameters(
        List.of(
          TaskSearchParameterList.builder()
            .key(TaskSearchKey.TASK_TYPE)
            .values(completeTaskOutboxPayload.taskTypes())
            .build(),
          TaskSearchParameterList.builder()
            .key(TaskSearchKey.CASE_ID)
            .values(List.of(completeTaskOutboxPayload.caseId()))
            .build()
        )
      )
      .taskSortingParameters(null)
      .requestContext(TaskRequestContext.ALL_WORK)
      .build();

    var tasksToComplete = taskManagementApiClient.searchTasks(searchRequest);

    if (!tasksToComplete.getStatusCode().is2xxSuccessful() || tasksToComplete.getBody() == null) {
      log.warn("Failed to retrieve tasks to cancel for case {} and task types {}",
        completeTaskOutboxPayload.caseId(), completeTaskOutboxPayload.taskTypes());
      return null;
    }

    TaskTerminationRequest request = TaskTerminationRequest.builder()
      .taskIds(tasksToComplete.getBody().getTasks().stream().map(TaskPayload::getExternalTaskId).toList())
      .action(COMPLETE.getId())
      .build();

    return taskManagementApiClient.terminateTask(request);
  }

  private ResponseEntity<TaskTerminationResponse> cancelTask(TaskOutboxRecord record) throws IOException {
    CancelTaskOutboxPayload cancelTaskOutboxPayload = objectMapper.readValue(record.payload(), CancelTaskOutboxPayload.class);

    var searchRequest = SearchTaskRequest.builder().searchParameters(
      List.of(
        TaskSearchParameterList.builder()
          .key(TaskSearchKey.PROCESS_CATEGORY_IDENTIFIER)
          .values(cancelTaskOutboxPayload.processCategoryIdentifiers())
          .build(),
        TaskSearchParameterList.builder()
          .key(TaskSearchKey.CASE_ID)
          .values(List.of(cancelTaskOutboxPayload.caseId()))
          .build()
        )
      )
      .taskSortingParameters(null)
      .requestContext(TaskRequestContext.ALL_WORK)
      .build();

    var tasksToCancel = taskManagementApiClient.searchTasks(searchRequest);

    if (!tasksToCancel.getStatusCode().is2xxSuccessful() || tasksToCancel.getBody() == null) {
      log.warn("Failed to retrieve tasks to cancel for process category identifiers {}", cancelTaskOutboxPayload.processCategoryIdentifiers());
      return null;
    }

    TaskTerminationRequest request = TaskTerminationRequest.builder()
      .taskIds(tasksToCancel.getBody().getTasks().stream().map(TaskPayload::getExternalTaskId).toList())
      .action(CANCEL.getId())
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
