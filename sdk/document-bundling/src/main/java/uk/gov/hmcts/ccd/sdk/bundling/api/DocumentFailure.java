package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * One document's contribution to a bundle failure, naming the document, the typed reason, and a
 * log-safe detail.
 */
public record DocumentFailure(
    String documentId,
    DocumentReference reference,
    BundleErrorCode code,
    String detail) {

  public DocumentFailure {
    Validate.requireNonBlank(documentId, "DocumentFailure.documentId");
    Validate.requireNonNull(code, "DocumentFailure.code");
    Validate.requireNonNull(detail, "DocumentFailure.detail");
  }

  /** A single-line description used in exception messages and logs. */
  public String describe() {
    String ref = reference == null ? "" : " (" + reference.provider() + "/" + reference.id() + ")";
    return documentId + ref + ": " + code + " - " + detail;
  }
}
