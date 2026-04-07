package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

public record TaskOutboxRecord(long id, long caseId, String payload, String requestedAction, int attemptCount) {
}
