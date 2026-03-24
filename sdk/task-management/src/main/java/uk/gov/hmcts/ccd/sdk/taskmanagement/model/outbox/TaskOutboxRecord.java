package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

public record TaskOutboxRecord(long id, String payload, String requestedAction, int attemptCount) {
}
