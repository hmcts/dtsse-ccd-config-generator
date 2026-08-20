package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundle;

/**
 * The current state of one durable bundle job, backed by its outbox row. The outbox row is the
 * secondary status record; the generation report on the result remains the authoritative record
 * of what was stitched.
 *
 * @param externalId the consumer-minted idempotency key
 * @param state the current state
 * @param attempts how many executions have been attempted
 * @param submittedAt when the job was submitted
 * @param lastUpdatedAt when the job last changed state
 * @param output the published CCD-shaped bundle, present once completed
 * @param failure the sanitised failure, present once failed
 */
public record BundleJob(
    UUID externalId,
    BundleJobState state,
    int attempts,
    Instant submittedAt,
    Instant lastUpdatedAt,
    Optional<CcdBundle> output,
    Optional<BundleJobFailure> failure) {

  public BundleJob {
    requireNonNull(externalId, "externalId");
    requireNonNull(state, "state");
    requireNonNull(submittedAt, "submittedAt");
    requireNonNull(lastUpdatedAt, "lastUpdatedAt");
    requireNonNull(output, "output");
    requireNonNull(failure, "failure");
    if (attempts < 0) {
      throw new IllegalArgumentException("BundleJob.attempts must not be negative");
    }
  }

  private static void requireNonNull(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException("BundleJob." + field + " must be provided");
    }
  }
}
