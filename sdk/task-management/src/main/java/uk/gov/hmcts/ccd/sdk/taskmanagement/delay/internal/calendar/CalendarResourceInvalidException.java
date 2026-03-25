package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

class CalendarResourceInvalidException extends RuntimeException {
  private static final long serialVersionUID = -3610389879304395229L;

  CalendarResourceInvalidException(String message, Throwable cause) {
    super(message, cause);
  }
}
