package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * The published bundle as reported by a {@link BundleDestination} after storage succeeded.
 *
 * <p>In production the artifact is always stored in CDAM, so the links here are CDAM document
 * links and {@link #toDocument()} maps them onto the standard CCD {@link Document} complex type.
 * Where that metadata is persisted — which case field, category, classification, and ACLs — is
 * the consuming service's decision.
 *
 * @param url the stored document's self link; the CCD {@code document_url}
 * @param binaryUrl the stored document's binary link; the CCD {@code document_binary_url}
 * @param filename the stored file name
 * @param mediaType the stored media type
 * @param size the stored size in bytes
 * @param sha256 the hex-encoded SHA-256 checksum of the stored content
 * @param hashToken the CDAM document hash, present when the service uses secure document access
 */
public record StoredBundle(
    String url,
    String binaryUrl,
    String filename,
    String mediaType,
    long size,
    String sha256,
    Optional<String> hashToken) {

  public StoredBundle {
    Validate.requireNonBlank(url, "StoredBundle.url");
    Validate.requireNonBlank(binaryUrl, "StoredBundle.binaryUrl");
    Validate.requireNonBlank(filename, "StoredBundle.filename");
    Validate.requireNonBlank(mediaType, "StoredBundle.mediaType");
    Validate.requireNonBlank(sha256, "StoredBundle.sha256");
    Validate.requireNonNull(hashToken, "StoredBundle.hashToken");
    if (size <= 0) {
      throw new IllegalArgumentException("StoredBundle.size must be positive");
    }
  }

  /**
   * Maps the stored bundle onto the CCD {@link Document} complex type, ready to set on a case
   * field. The category id is left unset; assign it when attaching if the case type categorises
   * its case file view.
   *
   * @return the document metadata for case data
   */
  public Document toDocument() {
    return Document.builder()
        .url(url)
        .binaryUrl(binaryUrl)
        .filename(filename)
        .hashToken(hashToken.orElse(null))
        .build();
  }
}
