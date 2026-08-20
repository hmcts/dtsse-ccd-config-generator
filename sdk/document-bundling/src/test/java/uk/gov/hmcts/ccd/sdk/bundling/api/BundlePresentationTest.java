package uk.gov.hmcts.ccd.sdk.bundling.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BundlePresentationTest {

  @Test
  void courtDefaultMatchesTheCurrentMicroserviceOutput() {
    BundlePresentation preset = BundlePresentation.courtDefault();

    assertThat(preset.tableOfContents()).isTrue();
    assertThat(preset.sectionCoverSheets()).isTrue();
    assertThat(preset.documentCoverSheets()).isFalse();
    assertThat(preset.pageNumbers()).isEqualTo(PageNumbers.BOTTOM_CENTRE_N_OF_M);
    assertThat(preset.confidentialMarking()).isEqualTo(ConfidentialMarking.APPROVED_HEADER);
  }

  @Test
  void withersReturnModifiedCopiesWithoutMutatingTheOriginal() {
    BundlePresentation original = BundlePresentation.courtDefault();
    BundlePresentation modified = original
        .withTableOfContents(false)
        .withSectionCoverSheets(false)
        .withDocumentCoverSheets(true)
        .withPageNumbers(PageNumbers.TOP_RIGHT_N)
        .withConfidentialMarking(ConfidentialMarking.NONE);

    assertThat(original).isEqualTo(BundlePresentation.courtDefault());
    assertThat(modified.tableOfContents()).isFalse();
    assertThat(modified.sectionCoverSheets()).isFalse();
    assertThat(modified.documentCoverSheets()).isTrue();
    assertThat(modified.pageNumbers()).isEqualTo(PageNumbers.TOP_RIGHT_N);
    assertThat(modified.confidentialMarking()).isEqualTo(ConfidentialMarking.NONE);
  }
}
