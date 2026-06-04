package uk.gov.hmcts.ccd.sdk.taskmanagement;

public class TaskOutboxTimeoutException extends RuntimeException {

  public TaskOutboxTimeoutException(String message) {
    super(message);
  }
}
