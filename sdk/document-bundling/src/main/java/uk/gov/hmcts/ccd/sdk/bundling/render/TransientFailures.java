package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.util.EnumSet;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException;

/**
 * Which failures are worth retrying: resolution, conversion and cover-page failures, unless a
 * Docmosis failure in the cause chain says the server answered permanently (4xx, non-PDF body,
 * bad payload). Shared by the job retry policy and the renderer's placeholder decisions, so a
 * failure the job would retry is never substituted on a retryable attempt.
 */
public final class TransientFailures {

  private static final Set<BundleErrorCode> TRANSIENT_CODES = EnumSet.of(
      BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleErrorCode.DOCUMENT_CONVERSION_FAILED,
      BundleErrorCode.COVER_PAGE_FAILED);

  private TransientFailures() {
  }

  public static boolean isTransient(BundleErrorCode code, Throwable cause) {
    if (!TRANSIENT_CODES.contains(code)) {
      return false;
    }
    for (Throwable next = cause; next != null; next = next.getCause()) {
      if (next instanceof DocmosisRenderException docmosis) {
        return docmosis.isTransientFailure();
      }
      if (next.getCause() == next) {
        break;
      }
    }
    return true;
  }
}
