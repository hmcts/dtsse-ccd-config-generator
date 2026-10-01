package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;

/**
 * Bounded retry with exponential backoff, applied only to transient resolution and conversion
 * failures; validation, not-found, access-denied, assembly and completion failures are terminal.
 */
public class BundleJobRetryPolicy {

  private static final long UNCAPPED_DELAY_CEILING_MILLIS = Duration.ofDays(1).toMillis();
  private static final Set<BundleErrorCode> TRANSIENT_CODES = EnumSet.of(
      BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleErrorCode.DOCUMENT_CONVERSION_FAILED);

  private final int maxAttempts;
  private final long initialDelayMillis;
  private final double multiplier;
  private final long maxDelayMillis;

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

  public boolean isTransient(BundleErrorCode code) {
    return TRANSIENT_CODES.contains(code);
  }

  public int maxAttempts() {
    return maxAttempts;
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
