package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

class CalendarResourceNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 6753971692367781003L;

  CalendarResourceNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
