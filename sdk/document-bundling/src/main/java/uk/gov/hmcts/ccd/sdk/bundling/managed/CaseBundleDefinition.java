package uk.gov.hmcts.ccd.sdk.bundling.managed;

import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/** Application selection rules; called inside a short, repeatable-read transaction. */
public interface CaseBundleDefinition {
  String name();

  /** The documents of the case, in order. */
  BundleRequest select(long caseReference);
}
