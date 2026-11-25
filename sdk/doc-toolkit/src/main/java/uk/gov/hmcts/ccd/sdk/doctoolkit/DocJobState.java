package uk.gov.hmcts.ccd.sdk.doctoolkit;

/** Where a job is. */
public enum DocJobState {
  /** Waiting for a worker, possibly until a retry time. */
  QUEUED,
  /** A worker holds it. */
  IN_PROGRESS,
  /** Rendered and handed to the service; a kept-up-to-date document waits here for a change. */
  DONE,
  /** Given up on; a kept-up-to-date document is tried again when its request changes. */
  FAILED
}
