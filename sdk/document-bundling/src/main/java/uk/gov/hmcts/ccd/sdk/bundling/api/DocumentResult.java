package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * The generation report entry for one stitched document; with its siblings it forms the
 * audit-grade record of exactly what was stitched, in what order.
 *
 * @param documentId the document's id from the request
 * @param reference the resolved source reference
 * @param mediaType the detected source media type
 * @param sha256 the hex-encoded SHA-256 checksum of the source content
 * @param pageCount the number of pages this document contributed
 * @param startPage the 1-based page the document starts at in the bundle
 */
public record DocumentResult(
    String documentId,
    DocumentReference reference,
    String mediaType,
    String sha256,
    int pageCount,
    int startPage) {

  public DocumentResult {
    Validate.requireNonBlank(documentId, "DocumentResult.documentId");
    Validate.requireNonNull(reference, "DocumentResult.reference");
    Validate.requireNonBlank(mediaType, "DocumentResult.mediaType");
    Validate.requireNonBlank(sha256, "DocumentResult.sha256");
    if (pageCount < 1) {
      throw new IllegalArgumentException("DocumentResult.pageCount must be at least 1");
    }
    if (startPage < 1) {
      throw new IllegalArgumentException("DocumentResult.startPage must be at least 1");
    }
  }
}
