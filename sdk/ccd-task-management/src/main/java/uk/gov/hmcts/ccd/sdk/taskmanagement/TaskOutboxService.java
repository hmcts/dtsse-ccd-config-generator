package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

public class TaskOutboxService {

    private final TaskOutboxRepository repository;
    private final ObjectMapper objectMapper;

    public TaskOutboxService(TaskOutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void enqueue(TaskPayload payload) {
        enqueue(new TaskCreateRequest(payload));
    }

    public void enqueue(TaskCreateRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        TaskPayload task = Objects.requireNonNull(request.task(), "task must not be null");
        requireText(task.getTaskId(), "taskId");
        requireText(task.getCaseId(), "caseId");
        requireText(task.getCaseTypeId(), "caseTypeId");

        try {
            String payload = objectMapper.writeValueAsString(request);
            repository.enqueue(task.getTaskId(), task.getCaseId(), task.getCaseTypeId(), payload);
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
