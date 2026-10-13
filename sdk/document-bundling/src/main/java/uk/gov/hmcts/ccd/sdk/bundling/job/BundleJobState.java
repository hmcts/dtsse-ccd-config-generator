package uk.gov.hmcts.ccd.sdk.bundling.job;

/** The states of a durable bundle job. */
public enum BundleJobState {
  QUEUED,
  IN_PROGRESS,
  COMPLETED,
  COMPLETED_WITH_WARNINGS,
  FAILED;

  public boolean terminal() {
    return this == COMPLETED || this == COMPLETED_WITH_WARNINGS || this == FAILED;
  }
}
