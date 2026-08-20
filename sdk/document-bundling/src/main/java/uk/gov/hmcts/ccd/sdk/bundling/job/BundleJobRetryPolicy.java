package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;

/**
 * Bounded retry with a small exponential backoff, applied only to typed transient failures.
 *
 * <p>Per the design's failure semantics — "Transient source/converter/storage failure → bounded
 * outbox retry; then fail carrying the transient history" — the transient set is
 * {@link BundleErrorCode#DOCUMENT_RESOLUTION_FAILED} (transient source),
 * {@link BundleErrorCode#DOCUMENT_CONVERSION_FAILED} (transient converter),
 * {@link BundleErrorCode#STORAGE_FAILED} (transient storage), and
 * {@link BundleErrorCode#TIMED_OUT} (a slow downstream is transient by nature; the per-attempt
 * behaviour — fail, publish nothing, carry per-stage timings — is unchanged). Validation,
 * rendering, and access failures are never blindly retried, and neither is
 * {@link BundleErrorCode#STORAGE_REJECTED}: a permanent destination rejection cannot succeed on
 * retry and each attempt would orphan a fresh upload.
 */
public class BundleJobRetryPolicy {

  /** The backoff ceiling applied when no maximum delay is configured. */
  private static final long UNCAPPED_DELAY_CEILING_MILLIS = Duration.ofDays(1).toMillis();

  private static final Set<BundleErrorCode> TRANSIENT_CODES =
      Collections.unmodifiableSet(EnumSet.of(
          BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
          BundleErrorCode.DOCUMENT_CONVERSION_FAILED,
          BundleErrorCode.STORAGE_FAILED,
          BundleErrorCode.TIMED_OUT));

  private final int maxAttempts;
  private final long initialDelayMillis;
  private final double multiplier;
  private final long maxDelayMillis;

  /**
   * Creates the policy.
   *
   * @param maxAttempts the total number of executions a job may consume before it fails
   * @param initialDelay the delay before the second attempt
   * @param multiplier the backoff multiplier applied to each subsequent delay
   * @param maxDelay the delay ceiling; zero means uncapped
   */
  public BundleJobRetryPolicy(int maxAttempts, Duration initialDelay, double multiplier,
      Duration maxDelay) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    this.maxAttempts = maxAttempts;
    this.initialDelayMillis = initialDelay.toMillis();
    this.multiplier = multiplier;
    this.maxDelayMillis = maxDelay.toMillis();
  }

  /**
   * Whether a failure code is in the transient set and therefore eligible for a bounded retry.
   *
   * @param code the failure's stable error code
   * @return true if the code is transient
   */
  public boolean isTransient(BundleErrorCode code) {
    return TRANSIENT_CODES.contains(code);
  }

  /**
   * The total number of executions a job may consume.
   *
   * @return the attempt bound
   */
  public int maxAttempts() {
    return maxAttempts;
  }

  /**
   * When the next attempt should run, or empty when the attempt bound is exhausted.
   *
   * @param attempts how many executions have already been attempted
   * @param now the current time
   * @return the next attempt time, or empty when retries are exhausted
   */
  public Optional<Instant> nextAttemptAt(int attempts, Instant now) {
    if (attempts >= maxAttempts) {
      return Optional.empty();
    }
    double delayMillis = initialDelayMillis;
    for (int attempt = 1; attempt < attempts; attempt++) {
      delayMillis = delayMillis * multiplier;
    }
    // Guard the exponential growth against overflow before it reaches Instant arithmetic: an
    // uncapped policy is still clamped to a sane ceiling rather than Infinity/Long.MAX_VALUE.
    long ceilingMillis = maxDelayMillis > 0 ? maxDelayMillis : UNCAPPED_DELAY_CEILING_MILLIS;
    long boundedMillis = !Double.isFinite(delayMillis) || delayMillis >= ceilingMillis
        ? ceilingMillis
        : Math.round(delayMillis);
    return Optional.of(now.plusMillis(boundedMillis));
  }
}
