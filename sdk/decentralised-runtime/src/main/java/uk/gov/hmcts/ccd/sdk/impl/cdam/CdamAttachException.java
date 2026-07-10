package uk.gov.hmcts.ccd.sdk.impl.cdam;

public class CdamAttachException extends RuntimeException {

  public CdamAttachException(String message) {
    super(message);
  }

  public CdamAttachException(String message, Throwable cause) {
    super(message, cause);
  }
}
