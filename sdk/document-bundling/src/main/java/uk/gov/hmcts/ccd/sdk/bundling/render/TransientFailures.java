package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderException;

/**
 * Which failures are worth retrying: resolution, conversion and cover-page failures, unless the
 * cause chain says otherwise. A conversion failure carries its own verdict
 * ({@link ConversionException#isTransientFailure()}), as does a cover-page Docmosis failure.
 * Shared by the job retry policy and the renderer's placeholder decisions, so a failure the job
 * would retry is never substituted on a retryable attempt.
 */
public final class TransientFailures {

  private static final Set<BundleErrorCode> TRANSIENT_CODES = EnumSet.of(
      BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleErrorCode.DOCUMENT_CONVERSION_FAILED,
      BundleErrorCode.COVER_PAGE_FAILED);

  private TransientFailures() {
  }

  public static boolean isTransient(BundleErrorCode code, Throwable cause) {
    return TRANSIENT_CODES.contains(code) && verdict(cause).orElse(true);
  }

  /** Whether the cause chain says a conversion or render failed for a temporary reason. */
  static boolean isOutage(Throwable cause) {
    return verdict(cause).orElse(false);
  }

  /** The first retry verdict in the cause chain, from a conversion or a Docmosis failure. */
  private static Optional<Boolean> verdict(Throwable cause) {
    for (Throwable next = cause; next != null; next = next.getCause()) {
      if (next instanceof ConversionException conversion) {
        return Optional.of(conversion.isTransientFailure());
      }
      if (next instanceof DocmosisRenderException docmosis) {
        return Optional.of(docmosis.isTransientFailure());
      }
      if (next.getCause() == next) {
        break;
      }
    }
    return Optional.empty();
  }
}
