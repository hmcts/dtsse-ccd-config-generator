package uk.gov.hmcts.divorce.tasks;

public enum TaskOutboxStatus {
    NEW,
    PROCESSING,
    PROCESSED,
    FAILED
}
