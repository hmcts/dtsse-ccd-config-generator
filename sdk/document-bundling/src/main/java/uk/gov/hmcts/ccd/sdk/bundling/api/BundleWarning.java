package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Optional;

/**
 * A non-fatal presentational note on a successful result — for example an included empty-section
 * page or an inspection finding. Warnings never describe omitted documents; a document that
 * cannot be stitched fails the bundle.
 *
 * @param code the stable, documented warning code, safe to alert on
 * @param message a log-safe description
 * @param documentId the document the warning concerns, when it concerns one
 */
public record BundleWarning(String code, String message, Optional<String> documentId) {

  public BundleWarning {
    Validate.requireNonBlank(code, "BundleWarning.code");
    Validate.requireNonBlank(message, "BundleWarning.message");
    Validate.requireNonNull(documentId, "BundleWarning.documentId");
  }

  /**
   * Creates a bundle-level warning.
   *
   * @param code the stable warning code
   * @param message a log-safe description
   * @return the warning
   */
  public static BundleWarning of(String code, String message) {
    return new BundleWarning(code, message, Optional.empty());
  }

  /**
   * Creates a warning concerning one document.
   *
   * @param code the stable warning code
   * @param message a log-safe description
   * @param documentId the document the warning concerns
   * @return the warning
   */
  public static BundleWarning forDocument(String code, String message, String documentId) {
    return new BundleWarning(code, message,
        Optional.of(Validate.requireNonBlank(documentId, "BundleWarning.documentId")));
  }
}
