package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a completion handler stored a bundle's PDF. A handler that returns one has it recorded
 * with the bundle in bundling.bundle, so the bundle can be found, served and later deleted.
 *
 * @param id the stored document's id
 * @param url the stored document's URL
 * @param binaryUrl the URL of the stored document's content
 */
public record StoredBundleDocument(UUID id, String url, String binaryUrl) {

  public StoredBundleDocument {
    Objects.requireNonNull(id, "StoredBundleDocument.id");
    Objects.requireNonNull(url, "StoredBundleDocument.url");
    Objects.requireNonNull(binaryUrl, "StoredBundleDocument.binaryUrl");
  }
}
