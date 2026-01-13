package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
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

      TaskManagementApiResponse response = createTask(record);
      if (response.isSuccess()) {
        repository.markProcessed(record.id(), response.statusCode());
        log.info("Task outbox {} processed with status {}", record.id(), response.statusCode());
      } else {
        int nextAttemptCount = record.attemptCount() + 1;
        LocalDateTime nextAttemptAt = retryPolicy.nextAttemptAt(nextAttemptCount, LocalDateTime.now());
        repository.markFailed(record.id(), response.statusCode(), response.body(), nextAttemptAt);
        if (nextAttemptAt == null) {
          log.warn(
              "Task outbox {} failed with status {}, retries exhausted",
              record.id(),
              response.statusCode()
          );
        } else {
          log.warn(
              "Task outbox {} failed with status {}, retrying at {}",
              record.id(),
              response.statusCode(),
              nextAttemptAt
          );
        }
      }
    }
  }

  private TaskManagementApiResponse createTask(TaskOutboxRecord record) {
    try {
      TaskCreateRequest request = objectMapper.readValue(record.payload(), TaskCreateRequest.class);
      return taskManagementApiClient.createTask(request);
    } catch (IOException ex) {
      log.error("Task outbox {} payload could not be parsed", record.id(), ex);
      return new TaskManagementApiResponse(null, ex.getMessage(), null);
    }
  }
}
