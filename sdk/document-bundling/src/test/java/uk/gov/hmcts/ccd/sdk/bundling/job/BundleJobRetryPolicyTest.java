package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;

/**
 * Retry decisions: only the typed transient set retries, with a small bounded backoff, per the
 * design's failure table ("Transient source/converter/storage failure → bounded outbox retry;
 * then fail carrying the transient history").
 */
class BundleJobRetryPolicyTest {

  private static final Set<BundleErrorCode> EXPECTED_TRANSIENT = Set.of(
      BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
      BundleErrorCode.DOCUMENT_CONVERSION_FAILED,
      BundleErrorCode.STORAGE_FAILED,
      BundleErrorCode.TIMED_OUT);

  private final BundleJobRetryPolicy policy =
      new BundleJobRetryPolicy(3, Duration.ofMillis(100), 2.0, Duration.ofSeconds(10));

  @Test
  void onlyTransientSourceConverterStorageAndTimeoutFailuresRetry() {
    for (BundleErrorCode code : BundleErrorCode.values()) {
      assertThat(policy.isTransient(code))
          .as("retry decision for %s", code)
          .isEqualTo(EXPECTED_TRANSIENT.contains(code));
    }
  }

  @Test
  void renderingAndValidationFailuresAreNeverRetried() {
    assertThat(policy.isTransient(BundleErrorCode.REQUEST_INVALID)).isFalse();
    assertThat(policy.isTransient(BundleErrorCode.DOCUMENT_NOT_FOUND)).isFalse();
    assertThat(policy.isTransient(BundleErrorCode.DOCUMENT_ACCESS_DENIED)).isFalse();
    assertThat(policy.isTransient(BundleErrorCode.DOCUMENT_INSPECTION_FAILED)).isFalse();
    assertThat(policy.isTransient(BundleErrorCode.ASSEMBLY_FAILED)).isFalse();
    assertThat(policy.isTransient(BundleErrorCode.OUTPUT_VALIDATION_FAILED)).isFalse();
    assertThat(policy.isTransient(BundleErrorCode.LIMIT_EXCEEDED)).isFalse();
    assertThat(policy.isTransient(BundleErrorCode.JOB_REQUEST_UNREADABLE)).isFalse();
  }

  @Test
  void permanentStorageRejectionsAreNeverRetried() {
    // A retried STORAGE_REJECTED could not succeed and would orphan a fresh upload per attempt.
    assertThat(policy.isTransient(BundleErrorCode.STORAGE_REJECTED)).isFalse();
  }

  @Test
  void backoffGrowsByTheMultiplierPerAttempt() {
    Instant now = Instant.parse("2026-08-13T10:00:00Z");
    assertThat(policy.nextAttemptAt(1, now)).contains(now.plusMillis(100));
    assertThat(policy.nextAttemptAt(2, now)).contains(now.plusMillis(200));
  }

  @Test
  void retriesExhaustAtTheAttemptBound() {
    Instant now = Instant.now();
    assertThat(policy.nextAttemptAt(3, now)).isEmpty();
    assertThat(policy.nextAttemptAt(4, now)).isEmpty();
  }

  @Test
  void backoffIsCappedAtTheMaximumDelay() {
    BundleJobRetryPolicy capped =
        new BundleJobRetryPolicy(10, Duration.ofMillis(100), 10.0, Duration.ofMillis(250));
    Instant now = Instant.parse("2026-08-13T10:00:00Z");
    assertThat(capped.nextAttemptAt(3, now)).contains(now.plusMillis(250));
  }

  @Test
  void atLeastOneAttemptIsRequired() {
    assertThatThrownBy(() ->
        new BundleJobRetryPolicy(0, Duration.ofMillis(1), 2.0, Duration.ofMillis(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
