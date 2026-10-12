package uk.gov.hmcts.ccd.sdk.bundling.managed;

import java.time.Instant;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobReport;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * Sensitive case data: expose only through an authorised projection with restrictive field ACLs.
 */
public record CaseBundleView(Optional<Publication> current, Lifecycle lifecycle) {
  public enum Lifecycle {
    NONE,
    PREPARING,
    UPDATING,
    UP_TO_DATE,
    FAILED
  }

  public record Publication(Document document, Instant publishedAt, BundleJobReport report) {}
}
