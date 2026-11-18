package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Objects;
import java.util.Optional;

/**
 * The queue row of one bundle: a definition's bundle of a case. It exists while a render of the
 * bundle is wanted; a bundle that is up to date has none. Each change to the bundle's documents
 * raises the revision. A failure is why its last render failed for good: it is not rendered again
 * until it changes.
 */
public record BundleJob(
    String definition,
    long caseReference,
    long revision,
    int attempts,
    Optional<String> failure) {

  public BundleJob {
    Objects.requireNonNull(definition, "BundleJob.definition");
    Objects.requireNonNull(failure, "BundleJob.failure");
  }

  @Override
  public String toString() {
    return definition + " of case " + caseReference;
  }
}
