package uk.gov.hmcts.ccd.sdk.bundling.job;

import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;

/**
 * The consumer's side of a finished job: called on the worker thread while the result is still
 * open, it stores the rendered PDF wherever the service keeps documents and returns a
 * JSON-serialisable summary (or null) that is persisted with the job. The job context carries the
 * selector parameters and execution context the job was submitted with, so the handler knows
 * which case (or other subject) the bundle belongs to. The worker closes the result afterwards; a
 * thrown exception fails the job terminally with COMPLETION_FAILED.
 */
@FunctionalInterface
public interface BundleJobCompletionHandler {

  Object onCompleted(BundleJob job, BundleJobContext context, BundleRequest request,
      BundleResult result) throws Exception;
}
