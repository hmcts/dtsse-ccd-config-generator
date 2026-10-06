package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException;

/**
 * Bounded retry with exponential backoff, applied only to transient resolution, conversion and
 * cover-page rendering failures; validation, not-found, access-denied, assembly and completion failures are terminal.
 */
public class BundleJobRetryPolicy {

  private static final long UNCAPPED_DELAY_CEILING_MILLIS = Duration.ofDays(1).toMillis();
  private static final Set<BundleErrorCode> TRANSIENT_CODES = EnumSet.of(
      BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleErrorCode.DOCUMENT_CONVERSION_FAILED,
      BundleErrorCode.COVER_PAGE_FAILED);

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

  /**
   * Whether a failure is worth retrying: a retryable code, unless a Docmosis failure in the
   * cause chain says the server answered permanently (4xx, non-PDF body, bad payload).
   */
  public boolean isTransient(BundleGenerationException failure) {
    if (!TRANSIENT_CODES.contains(failure.code())) {
      return false;
    }
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof DocmosisRenderException docmosis) {
        return docmosis.isTransientFailure();
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return true;
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
