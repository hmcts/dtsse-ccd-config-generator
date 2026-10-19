package uk.gov.hmcts.ccd.sdk.bundling.managed;

import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleMetadata;

/**
 * The case's current bundle, with its stored PDF, and whether it is being updated. Sensitive case
 * data: expose only through an authorised projection with restrictive field ACLs.
 */
public record CaseBundleView(Optional<BundleMetadata> current, Lifecycle lifecycle) {
  public enum Lifecycle {
    NONE,
    PREPARING,
    UPDATING,
    UP_TO_DATE,
    FAILED
  }
}
