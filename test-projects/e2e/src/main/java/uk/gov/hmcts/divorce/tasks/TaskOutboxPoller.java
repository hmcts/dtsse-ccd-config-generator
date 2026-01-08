package uk.gov.hmcts.divorce.tasks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@ConditionalOnProperty(name = "task-management.outbox.poller.enabled", havingValue = "true", matchIfMissing = true)
public class TaskOutboxPoller {

    private final TaskOutboxRepository repository;
    private final TaskManagementApiClient taskManagementApiClient;
    private final int batchSize;

    public TaskOutboxPoller(TaskOutboxRepository repository,
                            TaskManagementApiClient taskManagementApiClient,
                            @Value("${task-management.outbox.poller.batch-size:5}") int batchSize) {
        this.repository = repository;
        this.taskManagementApiClient = taskManagementApiClient;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${task-management.outbox.poller.delay:1000}")
    public void poll() {
        List<TaskOutboxRecord> records = repository.findPending(batchSize);
        for (TaskOutboxRecord record : records) {
            if (!repository.markProcessing(record.id())) {
                continue;
            }
            TaskManagementApiResponse response = taskManagementApiClient.createTask(record.payload());
            if (response.isSuccess()) {
                repository.markProcessed(record.id(), response.statusCode());
                log.info("Task outbox {} processed with status {}", record.id(), response.statusCode());
            } else {
                repository.markFailed(record.id(), response.statusCode(), response.body());
                log.warn("Task outbox {} failed with status {}", record.id(), response.statusCode());
            }
        }
    }
}
