package uk.gov.hmcts.ccd.sdk.bundling.job;

/**
 * Consumer callback for durable-job progress, invoked by the worker as a job changes state.
 * Implementations should be fast; a throwing listener is logged and never affects the job.
 */
@FunctionalInterface
public interface BundleProgressListener {

  void onProgress(BundleProgressEvent event);
}
