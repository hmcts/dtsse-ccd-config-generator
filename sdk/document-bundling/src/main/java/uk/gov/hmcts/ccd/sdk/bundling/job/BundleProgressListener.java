package uk.gov.hmcts.ccd.sdk.bundling.job;

/**
 * Consumer callback for durable-job progress, invoked by the worker as a job moves through its
 * states. Implementations must be fast and must not throw; presenting progress in a UI or case
 * data is the consumer's concern.
 */
@FunctionalInterface
public interface BundleProgressListener {

  /**
   * Receives one progress event.
   *
   * @param event the progress event
   */
  void onProgress(BundleProgressEvent event);
}
