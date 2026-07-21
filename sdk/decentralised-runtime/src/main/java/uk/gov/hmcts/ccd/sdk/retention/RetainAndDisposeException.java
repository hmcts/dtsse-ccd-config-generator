package uk.gov.hmcts.ccd.sdk.retention;

class RetainAndDisposeException extends RuntimeException {

  public RetainAndDisposeException(String message) {
    super(message);
  }

  public RetainAndDisposeException(String message, Throwable cause) {
    super(message, cause);
  }
}
