package uk.gov.hmcts.ccd.sdk.migration;

public class CcdDataMigrationException extends RuntimeException {

  public CcdDataMigrationException(String message) {
    super(message);
  }

  public CcdDataMigrationException(String message, Throwable cause) {
    super(message, cause);
  }
}
