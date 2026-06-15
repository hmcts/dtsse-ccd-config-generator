package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

import java.time.LocalDateTime;
import java.util.UUID;

public record TaskOutboxRecord(
    long id,
    long caseId,
    String eventId,
    LocalDateTime created,
    String payload,
    String requestedAction,
    int attemptCount,
    UUID claimToken
) {
}
