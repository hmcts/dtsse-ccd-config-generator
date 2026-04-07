package uk.gov.hmcts.ccd.sdk.taskmanagement;

import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxRecord;

public record TaskOutboxRetriesExhaustedEvent(
    TaskOutboxRecord record,
    Integer statusCode,
    int maxAttempts,
    Long nextRetryableOutboxId
) {
}
