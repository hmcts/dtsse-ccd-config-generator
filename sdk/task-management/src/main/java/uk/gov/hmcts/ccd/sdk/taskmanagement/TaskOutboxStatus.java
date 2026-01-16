package uk.gov.hmcts.ccd.sdk.taskmanagement;

public enum TaskOutboxStatus {
    NEW,
    PROCESSING,
    PROCESSED,
    FAILED
}
