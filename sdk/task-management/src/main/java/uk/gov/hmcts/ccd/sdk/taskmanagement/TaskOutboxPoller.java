package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;

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
    for (TaskOutboxRecord record : records) {
      if (!repository.markProcessing(record.id())) {
        continue;
      }

      try {
        ResponseEntity<TaskCreateResponse> response = createTask(record);
        log.warn("Task outbox {} create response body {}", record.id(), response.getBody());
        if (!hasTaskId(response)) {
          log.warn(
              "Task outbox {} create response missing task_id with status {}",
              record.id(),
              response.getStatusCodeValue()
          );
          handleFailure(record, response.getStatusCodeValue(), "Task creation response missing task_id");
          continue;
        }
        repository.markProcessed(record.id(), response.getStatusCodeValue());
        log.info("Task outbox {} processed with status {}", record.id(), response.getStatusCodeValue());
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

  private ResponseEntity<TaskCreateResponse> createTask(TaskOutboxRecord record) throws IOException {
    TaskCreateRequest request = objectMapper.readValue(record.payload(), TaskCreateRequest.class);
    log.warn("Task outbox {} sending payload {}", record.id(), objectMapper.writeValueAsString(request));
    return taskManagementApiClient.createTask(request);
  }

  private boolean hasTaskId(ResponseEntity<TaskCreateResponse> response) {
    TaskCreateResponse body = response.getBody();
    return body != null && body.taskId() != null && !body.taskId().isBlank();
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
