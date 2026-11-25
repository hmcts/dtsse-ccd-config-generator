package uk.gov.hmcts.ccd.sdk.doctoolkit.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleDocument;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleSection;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocConversionRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocFailure;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.HtmlPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.MissingDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PageNumbers;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PrintColour;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PrintOptions;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;
import uk.gov.hmcts.ccd.sdk.doctoolkit.Watermark;
import uk.gov.hmcts.ccd.sdk.type.Document;

class DocRequestJsonTest {
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

  @Test
  void aBundleSurvivesBeingQueued() {
    UUID claim = UUID.randomUUID();
    BundleRequest request = BundleRequest.builder("Hearing bundle")
        .fileName("hearing.pdf")
        .tableOfContents(false)
        .sectionCoverSheets(false)
        .documentCoverSheets(true)
        .pageNumbers(PageNumbers.TOP_RIGHT_N)
        .confidentialMarking(false)
        .missingDocuments(MissingDocuments.FAIL)
        .coverPage(HtmlPage.builder().title("Front").body("<p>Front</p>").css("p{}").build())
        .document(DocSource.bytes(new byte[] {1, 2}, "note.txt", "text/plain"))
        .section(BundleSection.builder("Claim")
            .document(BundleDocument.builder(DocSource.cdam(claim, "claim.pdf"))
                .title("Claim form").date(LocalDate.of(2026, 3, 14)).confidential(true).build())
            .section(BundleSection.builder("Empty").placeholderWhenEmpty().build())
            .build())
        .watermark(Watermark.image(PNG).onFirstPageOnly().underContent())
        .print(PrintOptions.bulkPrint().toBuilder().colour(PrintColour.GRAYSCALE)
            .maxOutputBytes(50L * 1024 * 1024).build())
        .build();

    BundleRequest read = (BundleRequest) DocRequestJson.read(DocRequestJson.write(request));

    assertThat(read.title()).isEqualTo("Hearing bundle");
    assertThat(read.fileName()).isEqualTo("hearing.pdf");
    assertThat(List.of(read.tableOfContents(), read.sectionCoverSheets(),
        read.documentCoverSheets(), read.confidentialMarking()))
        .containsExactly(false, false, true, false);
    assertThat(read.pageNumbers()).isEqualTo(PageNumbers.TOP_RIGHT_N);
    assertThat(read.missingDocuments()).isEqualTo(MissingDocuments.FAIL);
    assertThat(read.coverPage()).hasValueSatisfying(cover -> {
      HtmlPage page = (HtmlPage) cover;
      assertThat(List.of(page.title(), page.body(), page.css()))
          .containsExactly("Front", "<p>Front</p>", "p{}");
    });
    assertThat(read.root().documents()).singleElement().satisfies(note ->
        assertThat(note.source()).isEqualTo(DocSource.bytes(new byte[] {1, 2}, "note.txt",
            "text/plain")));
    BundleSection section = read.root().sections().getFirst();
    assertThat(section.documents()).singleElement().satisfies(document -> {
      assertThat(document.title()).isEqualTo("Claim form");
      assertThat(document.date()).contains(LocalDate.of(2026, 3, 14));
      assertThat(document.confidential()).isTrue();
      assertThat(document.source()).isEqualTo(DocSource.cdam(claim, "claim.pdf"));
    });
    assertThat(section.sections()).singleElement().satisfies(empty ->
        assertThat(empty.placeholderWhenEmpty()).isTrue());
    assertThat(read.watermark()).contains(Watermark.image(PNG).onFirstPageOnly().underContent());
    assertThat(read.print()).contains(request.print().orElseThrow());
  }

  @Test
  void aLetterSurvivesBeingQueued() {
    DocConversionRequest request = DocConversionRequest
        .unchanged(DocSource.bytes(new byte[] {1}, "coversheet.pdf"))
        .document(DocSource.cdam(UUID.randomUUID()))
        .title("CPC-01")
        .print(PrintOptions.bulkPrint())
        .build();

    DocConversionRequest read = (DocConversionRequest) DocRequestJson.read(
        DocRequestJson.write(request));

    assertThat(read.parts()).isEqualTo(request.parts());
    assertThat(read.title()).isEqualTo("CPC-01");
    assertThat(read.fileName()).isEqualTo(request.fileName());
    assertThat(read.print()).isEqualTo(request.print());
    assertThat(read.watermark()).isEmpty();
  }

  @Test
  void aResultSurvivesBeingStored() {
    UploadedDoc uploaded = new UploadedDoc(Document.builder().url("http://cdam/documents/1")
        .binaryUrl("http://cdam/documents/1/binary").filename("bundle.pdf")
        .uploadTimestamp(LocalDateTime.of(2026, 11, 24, 9, 30)).build(), 12,
        List.of(new DocFailure("Gone", DocFailure.Reason.NOT_FOUND, "404")));

    assertThat(DocRequestJson.readResult(DocRequestJson.writeResult(uploaded)))
        .isEqualTo(uploaded);
  }

  @Test
  void anUnknownStoredFormIsUnreadable() {
    assertThatThrownBy(() -> DocRequestJson.read("{\"type\":\"spreadsheet\"}"))
        .isInstanceOf(DocRequestJson.Unreadable.class);
    assertThatThrownBy(() -> DocRequestJson.read("not json"))
        .isInstanceOf(DocRequestJson.Unreadable.class);
  }
}
