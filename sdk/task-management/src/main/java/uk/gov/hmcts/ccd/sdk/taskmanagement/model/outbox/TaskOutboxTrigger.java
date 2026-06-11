package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record TaskOutboxTrigger(
    String caseId,
    String caseType,
    String eventId,
    LocalDateTime created
) {

  public static TaskOutboxTrigger create(String caseId, String caseType, String eventId) {
    return new TaskOutboxTrigger(caseId, caseType, eventId, LocalDateTime.now(ZoneOffset.UTC));
  }
}
