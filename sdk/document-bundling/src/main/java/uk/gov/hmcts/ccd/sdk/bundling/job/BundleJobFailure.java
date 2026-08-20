package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.List;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;

/**
 * The sanitised failure recorded on a failed job: typed, log-safe, and naming each responsible
 * document. Never a raw downstream error body or credential.
 *
 * @param code the stable error code
 * @param message the log-safe failure description
 * @param documentFailures the responsible documents; empty when the failure is bundle-level
 */
public record BundleJobFailure(
    BundleErrorCode code,
    String message,
    List<DocumentFailure> documentFailures) {

  public BundleJobFailure {
    if (code == null) {
      throw new IllegalArgumentException("BundleJobFailure.code must be provided");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("BundleJobFailure.message must be provided");
    }
    documentFailures = List.copyOf(documentFailures);
  }
}
