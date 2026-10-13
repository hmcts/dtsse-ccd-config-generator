package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.List;
import java.util.Objects;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;

/**
 * The sanitised failure recorded on a failed job: typed, log-safe, and naming each responsible
 * document. Never a raw downstream error body or credential.
 */
public record BundleJobFailure(
    BundleErrorCode code,
    String message,
    List<DocumentFailure> documentFailures) {

  public BundleJobFailure {
    Objects.requireNonNull(code, "BundleJobFailure.code");
    Objects.requireNonNull(message, "BundleJobFailure.message");
    documentFailures = List.copyOf(documentFailures);
  }
}
