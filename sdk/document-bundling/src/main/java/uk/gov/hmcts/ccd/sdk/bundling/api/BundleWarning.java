package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Optional;

/**
 * A non-fatal note on a successful result — for example an included empty-section page or an
 * inspection finding. Under {@link MissingDocumentPolicy#PLACEHOLDER} a document replaced by a
 * placeholder page also has a {@code DOCUMENT_MISSING} warning, whose message carries the
 * user-facing reason only; the technical detail is in {@link BundleResult#missingDocuments()}.
 * Under the default {@link MissingDocumentPolicy#FAIL} no document is ever omitted.
 */
public record BundleWarning(String code, String message, Optional<String> documentId) {

  public BundleWarning {
    Validate.requireNonBlank(code, "BundleWarning.code");
    Validate.requireNonBlank(message, "BundleWarning.message");
    Validate.requireNonNull(documentId, "BundleWarning.documentId");
  }

  /** Creates a bundle-level warning. */
  public static BundleWarning of(String code, String message) {
    return new BundleWarning(code, message, Optional.empty());
  }

  /** Creates a warning concerning one document. */
  public static BundleWarning forDocument(String code, String message, String documentId) {
    return new BundleWarning(code, message,
        Optional.of(Validate.requireNonBlank(documentId, "BundleWarning.documentId")));
  }
}
