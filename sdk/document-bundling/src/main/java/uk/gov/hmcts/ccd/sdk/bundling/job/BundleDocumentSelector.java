package uk.gov.hmcts.ccd.sdk.bundling.job;

import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;

/** Selects a job's case and documents when the worker executes it. */
@FunctionalInterface
public interface BundleDocumentSelector {
  SelectedCaseBundle select(BundleJob job);
}
