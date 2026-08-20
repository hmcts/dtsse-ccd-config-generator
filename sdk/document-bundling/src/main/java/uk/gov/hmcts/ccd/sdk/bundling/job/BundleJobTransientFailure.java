package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Instant;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;

/**
 * One transient failure recorded against a durable job attempt, persisted in the job's
 * transient history and carried into the final failure message when retries exhaust.
 *
 * @param attempt the 1-based attempt that failed
 * @param code the transient error code
 * @param message the sanitised failure message
 * @param at when the attempt failed
 */
record BundleJobTransientFailure(int attempt, BundleErrorCode code, String message, Instant at) {

  /**
   * A single-line description used in the exhausted-retries failure message.
   *
   * @return the description naming the attempt, code, and message
   */
  String describe() {
    return "attempt " + attempt + " at " + at + ": " + code + " - " + message;
  }
}
