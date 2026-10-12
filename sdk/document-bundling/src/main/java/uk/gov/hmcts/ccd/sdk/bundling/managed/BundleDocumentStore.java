package uk.gov.hmcts.ccd.sdk.bundling.managed;

import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;

/** External storage adapter; the managed layer owns ordering, recovery and cleanup. */
public interface BundleDocumentStore {
  StoredDocument upload(SelectedCaseBundle selection, BundleArtifact artifact) throws Exception;

  void attach(SelectedCaseBundle selection, StoredDocument document);

  void delete(UUID documentId);

  record StoredDocument(UUID id, String url, String binaryUrl, String fileName, String hash) {}
}
