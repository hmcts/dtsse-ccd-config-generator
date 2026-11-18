package uk.gov.hmcts.ccd.sdk.bundling.job;

/** The states of a bundle's queue row. */
public enum BundleJobState {
  QUEUED,
  IN_PROGRESS,
  COMPLETED,
  FAILED;

  public boolean terminal() {
    return this == COMPLETED || this == FAILED;
  }
}
