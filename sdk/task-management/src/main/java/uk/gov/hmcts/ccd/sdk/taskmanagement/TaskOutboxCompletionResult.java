package uk.gov.hmcts.ccd.sdk.taskmanagement;

import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxStatus;

public record TaskOutboxCompletionResult(
    long id,
    long caseId,
    String requestedAction,
    TaskOutboxStatus status,
    int attemptCount
) {
  public boolean processed() {
    return status == TaskOutboxStatus.PROCESSED;
  }

  public boolean failed() {
    return status == TaskOutboxStatus.UNPROCESSABLE;
  }
}
