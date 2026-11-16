package uk.gov.hmcts.ccd.sdk.bundling.job;

import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/** Compiles a job's bundle request when the worker executes it. */
@FunctionalInterface
public interface BundleDocumentSelector {
  BundleRequest select(BundleJobContext context);
}
