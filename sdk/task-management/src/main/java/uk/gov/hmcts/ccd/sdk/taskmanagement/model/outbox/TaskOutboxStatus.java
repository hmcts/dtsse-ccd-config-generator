package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

public enum TaskOutboxStatus {
  PENDING,
  PROCESSING,
  PROCESSED,
  UNPROCESSABLE
}
