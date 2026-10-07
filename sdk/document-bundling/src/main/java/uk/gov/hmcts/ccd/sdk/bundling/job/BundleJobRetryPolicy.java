package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.render.TransientFailures;

/**
 * Bounded retry with exponential backoff, applied only to transient resolution, conversion and
 * cover-page rendering failures; validation, not-found, access-denied, assembly and completion failures are terminal.
 */
public class BundleJobRetryPolicy {

  private static final long UNCAPPED_DELAY_CEILING_MILLIS = Duration.ofDays(1).toMillis();

  private final int maxAttempts;
  private final long initialDelayMillis;
  private final double multiplier;
  private final long maxDelayMillis;
  private final Duration requeueDelay;
  private final int maxRequeues;

  public BundleJobRetryPolicy(int maxAttempts, Duration initialDelay, double multiplier,
      Duration maxDelay) {
    this(maxAttempts, initialDelay, multiplier, maxDelay, Duration.ZERO, 0);
  }

  /**
   * A policy that also re-runs a coalesced job up to maxRequeues times, requeueDelay apart, while
   * its bundle has placeholders for documents that were only temporarily unavailable.
   */
  public BundleJobRetryPolicy(int maxAttempts, Duration initialDelay, double multiplier,
      Duration maxDelay, Duration requeueDelay, int maxRequeues) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    this.maxAttempts = maxAttempts;
    this.initialDelayMillis = initialDelay.toMillis();
    this.multiplier = multiplier;
    this.maxDelayMillis = maxDelay.toMillis();
    if (maxRequeues < 0 || requeueDelay.isNegative()) {
      throw new IllegalArgumentException("maxRequeues and requeueDelay must not be negative");
    }
    this.requeueDelay = requeueDelay;
    this.maxRequeues = maxRequeues;
  }

  /**
   * Whether a failure is worth retrying: a retryable code, unless a Docmosis failure in the
   * cause chain says the server answered permanently (4xx, non-PDF body, bad payload).
   */
  public boolean isTransient(BundleGenerationException failure) {
    return TransientFailures.isTransient(failure.code(), failure.getCause());
  }

  public int maxAttempts() {
    return maxAttempts;
  }

  /**
   * When to re-run a bundle that has placeholders for temporarily unavailable documents, after
   * it has already been re-run requeues times; empty once the bound is reached.
   */
  public Optional<Instant> requeueAt(int requeues, Instant now) {
    return requeues < maxRequeues ? Optional.of(now.plus(requeueDelay)) : Optional.empty();
  }

  /** When the next attempt should run, or empty when the attempt bound is exhausted. */
  public Optional<Instant> nextAttemptAt(int attempts, Instant now) {
    if (attempts >= maxAttempts) {
      return Optional.empty();
    }
    double delayMillis = initialDelayMillis;
    for (int attempt = 1; attempt < attempts; attempt++) {
      delayMillis = delayMillis * multiplier;
    }
    long ceilingMillis = maxDelayMillis > 0 ? maxDelayMillis : UNCAPPED_DELAY_CEILING_MILLIS;
    long boundedMillis = !Double.isFinite(delayMillis) || delayMillis >= ceilingMillis
        ? ceilingMillis : Math.round(delayMillis);
    return Optional.of(now.plusMillis(boundedMillis));
  }
}
