package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * One document's contribution to a bundle failure, naming the document, the typed reason, and a
 * log-safe detail.
 *
 * @param documentId the failing document's id from the request
 * @param reference the document's reference; may be null when the failure precedes resolution
 * @param code the typed error code for this document
 * @param detail a log-safe description; never a raw downstream error body or credential
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

  /**
   * A single-line description used in exception messages and logs.
   *
   * @return the description naming the document, reference, code, and detail
   */
  public String describe() {
    String ref = reference == null ? "" : " (" + reference.provider() + "/" + reference.id() + ")";
    return documentId + ref + ": " + code + " - " + detail;
  }
}
