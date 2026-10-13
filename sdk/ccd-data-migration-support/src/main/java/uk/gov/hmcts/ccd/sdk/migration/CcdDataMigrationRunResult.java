package uk.gov.hmcts.ccd.sdk.migration;

public record CcdDataMigrationRunResult(
    boolean lockAcquired,
    long batchesProcessed,
    long casesProcessed,
    long eventsProcessed,
    boolean caughtUp,
    boolean stoppedByTimeLimit
) {

  public static CcdDataMigrationRunResult skippedLocked() {
    return new CcdDataMigrationRunResult(false, 0, 0, 0, false, false);
  }
}
