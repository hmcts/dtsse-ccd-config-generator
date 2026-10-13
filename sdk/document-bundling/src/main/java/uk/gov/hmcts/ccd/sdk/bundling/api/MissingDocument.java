package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.List;

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
 * @param title the document title selected for this bundle, before adding any missing-page suffix
 * @param sectionPath outermost to innermost rendered section titles; empty for root documents
 */
public record MissingDocument(
    String documentId,
    DocumentReference reference,
    MissingDocumentReason reason,
    BundleErrorCode code,
    String detail,
    int startPage,
    String title,
    List<String> sectionPath) {

  /** Compatibility constructor for callers without snapshot metadata. */
  public MissingDocument(String documentId, DocumentReference reference, MissingDocumentReason reason,
      BundleErrorCode code, String detail, int startPage) {
    this(documentId, reference, reason, code, detail, startPage, documentId, List.of());
  }

  public MissingDocument {
    Validate.requireNonBlank(documentId, "MissingDocument.documentId");
    // Reports written before snapshot metadata was introduced have neither field.
    title = title == null ? documentId : Validate.requireNonBlank(title, "MissingDocument.title");
    sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
    Validate.requireNonNull(reason, "MissingDocument.reason");
    Validate.requireNonNull(code, "MissingDocument.code");
    Validate.requireNonNull(detail, "MissingDocument.detail");
    if (startPage < 1) {
      throw new IllegalArgumentException("MissingDocument.startPage must be at least 1");
    }
  }
}
