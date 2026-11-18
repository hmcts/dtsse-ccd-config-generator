package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The queue row of one bundle: a definition's bundle of a case. The state says whether a render
 * is wanted, running, done or failed; a change to the bundle's documents queues it again.
 * submittedAt is when it was last queued, and the failure is the sanitised failure record.
 */
public record BundleJob(
    UUID externalId,
    BundleJobState state,
    int attempts,
    Instant submittedAt,
    Instant lastUpdatedAt,
    String definition,
    long caseReference,
    Optional<BundleJobFailure> failure) {

  public BundleJob {
    Objects.requireNonNull(externalId, "BundleJob.externalId");
    Objects.requireNonNull(state, "BundleJob.state");
    Objects.requireNonNull(submittedAt, "BundleJob.submittedAt");
    Objects.requireNonNull(lastUpdatedAt, "BundleJob.lastUpdatedAt");
    Objects.requireNonNull(definition, "BundleJob.definition");
    Objects.requireNonNull(failure, "BundleJob.failure");
  }
}
