package uk.gov.hmcts.reform.cftlib.pdf.exception;

public class MalformedTemplateException extends RuntimeException {

  public MalformedTemplateException(String message, Throwable cause) {
    super(message, cause);
  }
}
