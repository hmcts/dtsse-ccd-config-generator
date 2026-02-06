package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

public enum TaskOutboxStatus {
    NEW,
    PROCESSING,
    PROCESSED,
    FAILED
}
