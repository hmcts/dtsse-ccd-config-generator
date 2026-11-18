package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Collection;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;

/** Selects a bundle's case and documents when the worker renders it. */
@FunctionalInterface
interface BundleDocumentSelector {
  SelectedCaseBundle select(BundleJob job);

  /** The definitions this worker can select for, or null for any. Other jobs are left queued. */
  default Collection<String> definitions() {
    return null;
  }
}
