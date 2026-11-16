package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Objects;
import java.util.UUID;

/** What a document selector sees when a claimed job executes: the job and the case it is for. */
public record BundleJobContext(UUID externalId, String definition, String caseKey) {

  public BundleJobContext {
    Objects.requireNonNull(externalId, "BundleJobContext.externalId");
    Objects.requireNonNull(definition, "BundleJobContext.definition");
    Objects.requireNonNull(caseKey, "BundleJobContext.caseKey");
  }
}
