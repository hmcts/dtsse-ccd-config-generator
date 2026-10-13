package uk.gov.hmcts.ccd.sdk.bundling.job;

import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/**
 * Produces a job's bundle request when the worker executes it. The default returns the request
 * exactly as submitted (a snapshot at submission); a consumer that registers its own selector
 * submits only selector parameters and compiles the document list at execution time.
 */
@FunctionalInterface
public interface BundleDocumentSelector {
  BundleRequest select(BundleJobContext context);

  static BundleDocumentSelector asSubmitted() {
    return context -> context.submittedRequest().orElseThrow(() -> new IllegalStateException(
        "Job " + context.externalId() + " was submitted without a bundle request; register a "
            + "BundleDocumentSelector that compiles the request at execution time"));
  }
}
