package uk.gov.hmcts.ccd.sdk.taskmanagement;

public record TaskOutboxRecord(long id, String taskId, String payload, int attemptCount) {
}
