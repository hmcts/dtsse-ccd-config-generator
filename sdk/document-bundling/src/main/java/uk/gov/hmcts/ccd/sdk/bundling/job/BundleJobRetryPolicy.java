package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.render.TransientFailures;

/**
 * How a render that did not finish is tried again. A dependency that is down is retried, delay
 * apart, for as long as it takes. maxAttempts bounds only renders that end without recording
 * anything, as one that crashes its pod does. Validation, not-found, access-denied and assembly
 * failures are not retried.
 */
public record BundleJobRetryPolicy(int maxAttempts, Duration delay) {

  public BundleJobRetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
  }

  /**
   * Whether a failure is worth retrying: a retryable code, unless a Docmosis failure in the
   * cause chain says the server answered permanently (4xx, non-PDF body, bad payload).
   */
  public boolean isTransient(BundleGenerationException failure) {
    return TransientFailures.isTransient(failure.code(), failure.getCause());
  }
}
