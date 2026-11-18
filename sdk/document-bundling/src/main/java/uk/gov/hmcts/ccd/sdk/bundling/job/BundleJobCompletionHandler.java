package uk.gov.hmcts.ccd.sdk.bundling.job;

import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;

/**
 * Stores a finished job's PDF: called on the worker thread while the result is still open, it
 * returns where the PDF was stored, which is recorded with the bundle. The worker closes the
 * result afterwards; a thrown exception fails the job with COMPLETION_FAILED, after retries if
 * {@link #retryable} says so.
 */
@FunctionalInterface
interface BundleJobCompletionHandler {

  StoredBundleDocument onCompleted(BundleJob job, SelectedCaseBundle selected, BundleResult result)
      throws Exception;

  /** Whether a failure thrown by onCompleted is worth retrying, as for a transient render failure. */
  default boolean retryable(Exception failure) {
    return false;
  }

  /**
   * Called when onCompleted returned but the job could not be marked completed, because this
   * worker no longer holds its lease. What the handler stored is then not the job's bundle, so
   * it can remove it. Best effort: a failure is logged.
   */
  default void discarded(BundleJob job, StoredBundleDocument stored) throws Exception {
  }
}
