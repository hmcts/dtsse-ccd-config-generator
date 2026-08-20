package uk.gov.hmcts.ccd.sdk.bundling.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BundleRequestTest {

  private static BundleDocument document(String id) {
    return BundleDocument.builder()
        .id(id)
        .title("Document " + id)
        .date(LocalDate.of(2026, 1, 15))
        .reference(new DocumentReference("case-documents", id))
        .build();
  }

  private static BundleRequest.Builder validRequest(BundleSection root) {
    return BundleRequest.builder()
        .externalId(UUID.randomUUID())
        .title("Hearing bundle")
        .fileName("case-1234-hearing-bundle.pdf")
        .root(root);
  }

  @Test
  void buildsTheDesignDocumentExampleTree() {
    BundleRequest request = validRequest(BundleSection.builder("Case file")
        .section(BundleSection.builder("Applications")
            .document(document("app-1"))
            .document(BundleDocument.builder()
                .id("app-2")
                .title("Confidential application")
                .reference(new DocumentReference("case-documents", "app-2"))
                .confidential(true)
                .build())
            .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER)
            .build())
        .build())
        .presentation(BundlePresentation.courtDefault()
            .withTableOfContents(true)
            .withSectionCoverSheets(true)
            .withPageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M)
            .withConfidentialMarking(ConfidentialMarking.APPROVED_HEADER))
        .build();

    assertThat(request.allDocuments()).extracting(BundleDocument::id)
        .containsExactly("app-1", "app-2");
    assertThat(request.root().sections()).hasSize(1);
    assertThat(request.allDocuments().get(1).confidential()).isTrue();
  }

  @Test
  void defaultsPresentationToCourtDefault() {
    BundleRequest request = validRequest(
        BundleSection.builder("Case file").document(document("a")).build()).build();

    assertThat(request.presentation()).isEqualTo(BundlePresentation.courtDefault());
  }

  @Test
  void documentOrderIsDeterministicDocumentsBeforeChildSections() {
    BundleRequest request = validRequest(BundleSection.builder("Root")
        .document(document("top-1"))
        .section(BundleSection.builder("Child A").document(document("a-1")).build())
        .section(BundleSection.builder("Child B").document(document("b-1")).build())
        .build()).build();

    assertThat(request.allDocuments()).extracting(BundleDocument::id)
        .containsExactly("top-1", "a-1", "b-1");
  }

  @Test
  void rejectsDuplicateDocumentIdsAcrossNestedSectionsNamingThem() {
    BundleSection root = BundleSection.builder("Root")
        .document(document("dup"))
        .section(BundleSection.builder("Child").document(document("dup")).build())
        .build();

    assertThatThrownBy(() -> validRequest(root).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique")
        .hasMessageContaining("dup");
  }

  @Test
  void rejectsMissingRequiredFields() {
    BundleSection root = BundleSection.builder("Root").document(document("a")).build();

    assertThatThrownBy(() -> BundleRequest.builder()
        .title("t").fileName("f.pdf").root(root).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("externalId");
    assertThatThrownBy(() -> BundleRequest.builder()
        .externalId(UUID.randomUUID()).fileName("f.pdf").root(root).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("title");
    assertThatThrownBy(() -> BundleRequest.builder()
        .externalId(UUID.randomUUID()).title("t").fileName("f.pdf").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("root");
  }

  @Test
  void rejectsUnsafeFileNames() {
    BundleSection root = BundleSection.builder("Root").document(document("a")).build();

    assertThatThrownBy(() -> validRequest(root).fileName("../escape.pdf").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("path separators");
    assertThatThrownBy(() -> validRequest(root).fileName("bundle.docx").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(".pdf");
  }

  @Test
  void rejectsABundleWithNoDocumentsAndNoPlaceholderSection() {
    BundleSection root = BundleSection.builder("Root")
        .section(BundleSection.builder("Empty child").build())
        .build();

    assertThatThrownBy(() -> validRequest(root).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one document");
  }

  @Test
  void acceptsAnEmptyBundleWhenASectionRendersAPlaceholder() {
    BundleSection root = BundleSection.builder("Root")
        .section(BundleSection.builder("Expected but empty")
            .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER)
            .build())
        .build();

    assertThat(validRequest(root).build().allDocuments()).isEmpty();
  }

  @Test
  void sectionsDefaultToOmittingWhenEmpty() {
    BundleSection section = BundleSection.builder("Applications").build();

    assertThat(section.emptySectionPolicy()).isEqualTo(EmptySectionPolicy.OMIT);
  }

  @Test
  void documentRequiresIdTitleAndReference() {
    assertThatThrownBy(() -> BundleDocument.builder()
        .title("t").reference(new DocumentReference("p", "1")).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
    assertThatThrownBy(() -> BundleDocument.builder()
        .id("1").reference(new DocumentReference("p", "1")).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("title");
    assertThatThrownBy(() -> BundleDocument.builder().id("1").title("t").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reference");
  }
}
