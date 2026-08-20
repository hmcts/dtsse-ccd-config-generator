package uk.gov.hmcts.ccd.sdk.bundling.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BundleGenerationExceptionTest {

  @Test
  void theMessageAloneNamesWhatFailedOnWhichDocumentAtWhichStageAndWhatToDo() {
    BundleGenerationException exception = new BundleGenerationException(
        BundleErrorCode.DOCUMENT_NOT_FOUND,
        BundleStage.RESOLVE,
        "2 of 12 documents could not be resolved.",
        "Check the documents still exist in the case, then resubmit the bundle.",
        List.of(
            new DocumentFailure("app-1", new DocumentReference("case-documents", "abc-123"),
                BundleErrorCode.DOCUMENT_NOT_FOUND, "No document with this id"),
            new DocumentFailure("app-2", new DocumentReference("case-documents", "def-456"),
                BundleErrorCode.DOCUMENT_NOT_FOUND, "No document with this id")));

    assertThat(exception.getMessage())
        .contains("DOCUMENT_NOT_FOUND")
        .contains("RESOLVE")
        .contains("app-1")
        .contains("case-documents/abc-123")
        .contains("app-2")
        .contains("Remediation: Check the documents still exist");
    assertThat(exception.code()).isEqualTo(BundleErrorCode.DOCUMENT_NOT_FOUND);
    assertThat(exception.stage()).isEqualTo(BundleStage.RESOLVE);
    assertThat(exception.documentFailures()).hasSize(2);
  }

  @Test
  void bundleLevelFailuresCarryNoDocumentList() {
    BundleGenerationException exception = new BundleGenerationException(
        BundleErrorCode.STORAGE_FAILED,
        BundleStage.STORE,
        "The destination rejected the artifact.",
        "Check the destination adapter's connectivity.",
        List.of());

    assertThat(exception.getMessage())
        .contains("STORAGE_FAILED")
        .doesNotContain("Failed documents");
    assertThat(exception.documentFailures()).isEmpty();
  }

  @Test
  void documentFailureDescriptionsHandleAMissingReference() {
    DocumentFailure failure = new DocumentFailure(
        "media-1", null, BundleErrorCode.REQUEST_INVALID, "Media document has no access URL");

    assertThat(failure.describe())
        .isEqualTo("media-1: REQUEST_INVALID - Media document has no access URL");
  }
}
