package uk.gov.hmcts.ccd.sdk.doctoolkit;

/** Where a queued job is. */
public enum DocJobState {
  /** Waiting to be run, or to be retried. */
  QUEUED,
  /** A worker is running it. */
  IN_PROGRESS,
  /**
   * Stored in CDAM and handed to the service's {@link DocJobHandler}.
   */
  COMPLETED,
  /**
   * Given up on; the handler's {@link DocJobHandler#failed} was called.
   */
  FAILED
}
