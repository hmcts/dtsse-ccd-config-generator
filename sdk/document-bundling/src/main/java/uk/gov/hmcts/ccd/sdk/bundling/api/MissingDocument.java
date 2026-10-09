package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * A request document that was replaced by a placeholder page, under
 * {@link MissingDocumentPolicy#PLACEHOLDER}.
 *
 * @param documentId the request document's id
 * @param reference where the document was to be fetched from
 * @param reason why it is missing, in user-facing terms
 * @param code the technical error code
 * @param detail a log-safe technical detail, not for users
 * @param startPage the bundle page its placeholder starts on
 */
public record MissingDocument(
    String documentId,
    DocumentReference reference,
    MissingDocumentReason reason,
    BundleErrorCode code,
    String detail,
    int startPage) {

  public MissingDocument {
    Validate.requireNonBlank(documentId, "MissingDocument.documentId");
    Validate.requireNonNull(reason, "MissingDocument.reason");
    Validate.requireNonNull(code, "MissingDocument.code");
    Validate.requireNonNull(detail, "MissingDocument.detail");
    if (startPage < 1) {
      throw new IllegalArgumentException("MissingDocument.startPage must be at least 1");
    }
  }
}
