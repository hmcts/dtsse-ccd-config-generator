package uk.gov.hmcts.ccd.sdk.doctoolkit.cdam;

import uk.gov.hmcts.ccd.sdk.doctoolkit.DocFailure;

/** A CDAM document could not be fetched. */
public class CdamFetchException extends Exception {
  private final DocFailure.Reason reason;

  public CdamFetchException(DocFailure.Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  /** Why, as reported for the document. */
  public DocFailure.Reason reason() {
    return reason;
  }
}
