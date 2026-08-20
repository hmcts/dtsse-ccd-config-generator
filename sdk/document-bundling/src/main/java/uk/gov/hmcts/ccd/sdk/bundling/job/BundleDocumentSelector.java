package uk.gov.hmcts.ccd.sdk.bundling.job;

import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/**
 * Produces a job's bundle request when the worker executes it.
 *
 * <p>The SDK registers {@link #asSubmitted()} as the overridable base case, so the simple path —
 * build the tree in the event handler, submit it — needs no extension and generation is a
 * snapshot at submission. A service that overrides the selector submits only the external id and
 * selector parameters; the worker compiles the document list when the job runs, making generation
 * a snapshot at execution and keeping CCD callbacks small. Either way there is one worker code
 * path: claim row, call selector, render.
 */
@FunctionalInterface
public interface BundleDocumentSelector {

  /**
   * Compiles the bundle request for one executing job.
   *
   * @param context the job's submitted request, parameters, and execution context
   * @return the request to render
   */
  BundleRequest select(BundleJobContext context);

  /**
   * The default selector: returns the request exactly as it was submitted.
   *
   * @return the snapshot-at-submission selector
   */
  static BundleDocumentSelector asSubmitted() {
    return context -> context.submittedRequest().orElseThrow(() -> new IllegalStateException(
        "Job " + context.externalId() + " was submitted without a bundle request; register a "
            + "BundleDocumentSelector that compiles the request at execution time"));
  }
}
