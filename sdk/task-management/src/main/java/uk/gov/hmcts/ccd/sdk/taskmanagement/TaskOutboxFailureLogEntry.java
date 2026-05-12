package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.LocalDateTime;

public record TaskOutboxFailureLogEntry(
    long historyId,
    long taskOutboxId,
    long caseId,
    String requestedAction,
    String payload,
    Integer responseCode,
    String error,
    LocalDateTime created
) {
}
