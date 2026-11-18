package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Objects;
import java.util.Optional;

/**
 * The queue row of one bundle: a definition's bundle of a case. The state says whether a render
 * is wanted, running, done or failed; a change to the bundle's documents queues it again. The
 * failure is the sanitised failure record.
 */
public record BundleJob(
    String definition,
    long caseReference,
    BundleJobState state,
    int attempts,
    Optional<BundleJobFailure> failure) {

  public BundleJob {
    Objects.requireNonNull(definition, "BundleJob.definition");
    Objects.requireNonNull(state, "BundleJob.state");
    Objects.requireNonNull(failure, "BundleJob.failure");
  }

  @Override
  public String toString() {
    return definition + " of case " + caseReference;
  }
}
