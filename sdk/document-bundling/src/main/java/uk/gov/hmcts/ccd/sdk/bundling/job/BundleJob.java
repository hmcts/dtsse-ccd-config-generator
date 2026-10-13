package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The current state of one durable bundle job, read from its outbox row. The result is the JSON
 * summary the consumer's completion handler returned; the failure is the sanitised failure record.
 * The coalesce key is present when the job was submitted with one, and coalescedSubmissions counts
 * the later submissions it absorbed. claimedAt is when the current (or last) attempt was claimed;
 * a selector-driven job compiles its documents after that, so the completed job with the greatest
 * claimedAt reflects the newest state, whatever order jobs finished in.
 */
public record BundleJob(
    UUID externalId,
    BundleJobState state,
    int attempts,
    Instant submittedAt,
    Instant lastUpdatedAt,
    Optional<String> coalesceKey,
    int coalescedSubmissions,
    Optional<Instant> claimedAt,
    Optional<String> result,
    Optional<BundleJobFailure> failure) {

  public BundleJob {
    Objects.requireNonNull(externalId, "BundleJob.externalId");
    Objects.requireNonNull(state, "BundleJob.state");
    Objects.requireNonNull(submittedAt, "BundleJob.submittedAt");
    Objects.requireNonNull(lastUpdatedAt, "BundleJob.lastUpdatedAt");
    Objects.requireNonNull(coalesceKey, "BundleJob.coalesceKey");
    Objects.requireNonNull(claimedAt, "BundleJob.claimedAt");
    Objects.requireNonNull(result, "BundleJob.result");
    Objects.requireNonNull(failure, "BundleJob.failure");
  }
}
