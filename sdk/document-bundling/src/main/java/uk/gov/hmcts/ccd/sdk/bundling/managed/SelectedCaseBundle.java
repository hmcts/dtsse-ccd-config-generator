package uk.gov.hmcts.ccd.sdk.bundling.managed;

import java.util.Objects;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/** The case attachment destination and the ordered sources selected for it. */
public record SelectedCaseBundle(
    long caseReference, String caseType, String jurisdiction, BundleRequest request) {
  public SelectedCaseBundle {
    Objects.requireNonNull(caseType);
    Objects.requireNonNull(jurisdiction);
    Objects.requireNonNull(request);
  }
}
