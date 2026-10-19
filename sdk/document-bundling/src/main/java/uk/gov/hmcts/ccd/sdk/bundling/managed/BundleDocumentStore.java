package uk.gov.hmcts.ccd.sdk.bundling.managed;

import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.ccd.sdk.bundling.job.StoredBundleDocument;

/** External storage adapter; the managed layer owns ordering and cleanup. */
public interface BundleDocumentStore {
  /** Stores the PDF and attaches it to the selection's case, leaving nothing stored on failure. */
  StoredBundleDocument store(SelectedCaseBundle selection, BundleArtifact artifact)
      throws Exception;

  void delete(UUID documentId);
}
