package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The current state of one durable bundle job, read from its outbox row. The failure is the sanitised failure record.
 * The definition and case key say which bundle it is of, and coalescedSubmissions counts the later
 * changes it absorbed. claimedAt is when the current (or last) attempt was claimed; the job
 * selects its documents after that, so the completed job with the greatest claimedAt reflects the
 * newest state, whatever order jobs finished in.
 */
public record BundleJob(
    UUID externalId,
    BundleJobState state,
    int attempts,
    Instant submittedAt,
    Instant lastUpdatedAt,
    String definition,
    String caseKey,
    int coalescedSubmissions,
    Optional<Instant> claimedAt,
    Optional<BundleJobFailure> failure) {

  public BundleJob {
    Objects.requireNonNull(externalId, "BundleJob.externalId");
    Objects.requireNonNull(state, "BundleJob.state");
    Objects.requireNonNull(submittedAt, "BundleJob.submittedAt");
    Objects.requireNonNull(lastUpdatedAt, "BundleJob.lastUpdatedAt");
    Objects.requireNonNull(definition, "BundleJob.definition");
    Objects.requireNonNull(caseKey, "BundleJob.caseKey");
    Objects.requireNonNull(claimedAt, "BundleJob.claimedAt");
    Objects.requireNonNull(failure, "BundleJob.failure");
  }
}
