package uk.gov.hmcts.ccd.sdk.taskmanagement;

@FunctionalInterface
public interface TaskOutboxTelemetry {
  void retriesExhausted(TaskOutboxRetriesExhaustedEvent event);
}
