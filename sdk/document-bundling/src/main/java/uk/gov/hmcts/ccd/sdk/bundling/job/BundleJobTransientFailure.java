package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Instant;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;

/** One transient failure in a job's retry history, carried into the final failure message. */
record BundleJobTransientFailure(int attempt, BundleErrorCode code, String message, Instant at) {

  String describe() {
    return "attempt " + attempt + " at " + at + ": " + code + " - " + message;
  }
}
