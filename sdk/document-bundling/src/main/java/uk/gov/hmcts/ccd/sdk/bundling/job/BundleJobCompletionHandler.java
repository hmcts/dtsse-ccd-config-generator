package uk.gov.hmcts.ccd.sdk.bundling.job;

import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;

/**
 * The consumer's side of a finished job: called on the worker thread while the result is still
 * open, it stores the rendered PDF wherever the service keeps documents and returns a
 * JSON-serialisable summary (or null) that is persisted with the job. Returning a
 * {@link StoredBundleDocument} also records where the PDF is with the bundle. The job context
 * carries the selector parameters and execution context the job was submitted with, so the
 * handler knows which case (or other subject) the bundle belongs to. The worker closes the result
 * afterwards; a thrown exception fails the job with COMPLETION_FAILED, after retries if
 * {@link #retryable} says so.
 */
@FunctionalInterface
public interface BundleJobCompletionHandler {

  Object onCompleted(BundleJob job, BundleJobContext context, BundleRequest request,
      BundleResult result) throws Exception;

  /** Whether a failure thrown by onCompleted is worth retrying, as for a transient render failure. */
  default boolean retryable(Exception failure) {
    return false;
  }

  /**
   * Called when onCompleted returned but the job could not be marked completed, because this
   * worker no longer holds its lease. Whatever the handler stored is then not the job's bundle,
   * so it can remove it. Best effort: a failure is logged.
   */
  default void discarded(BundleJob job, Object summary) throws Exception {
  }
}
