package uk.gov.hmcts.ccd.sdk.bundling.render;

import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException;

/**
 * Which failures are a dependency being down, and so worth retrying: a source its store could not
 * serve for the moment, and a conversion or cover page that failed because Docmosis did. Anything
 * else would fail the same way again. Shared by the job retry policy and the renderer's
 * placeholder decisions, so a failure the job would retry is never substituted on a retryable
 * attempt.
 */
public final class TransientFailures {

  private TransientFailures() {
  }

  public static boolean isTransient(BundleErrorCode code, Throwable cause) {
    return code == BundleErrorCode.DOCUMENT_UNAVAILABLE
        || (code == BundleErrorCode.DOCUMENT_CONVERSION_FAILED
            || code == BundleErrorCode.COVER_PAGE_FAILED) && isDocmosisOutage(cause);
  }

  /** Whether the cause chain says Docmosis failed in a way that is worth retrying. */
  static boolean isDocmosisOutage(Throwable cause) {
    for (Throwable next = cause; next != null; next = next.getCause()) {
      if (next instanceof DocmosisRenderException docmosis) {
        return docmosis.isTransientFailure();
      }
      if (next.getCause() == next) {
        break;
      }
    }
    return false;
  }
}
