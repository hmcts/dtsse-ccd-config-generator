package uk.gov.hmcts.ccd.sdk.doctoolkit.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.doctoolkit.Classification;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocFailure;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.HtmlPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.MissingDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PageNumbers;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfPart;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfSection;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PrintColour;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PrintOptions;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;
import uk.gov.hmcts.ccd.sdk.doctoolkit.Watermark;
import uk.gov.hmcts.ccd.sdk.type.Document;

class DocRequestJsonTest {
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
  private static final UUID CLAIM = UUID.fromString("6f1c3c38-3d0e-4b8e-9a4b-4a3f0e2c1d10");
  private static final UUID ORDER = UUID.fromString("0b6a1f5e-8c2d-4e7f-a1b2-c3d4e5f60718");

  @Test
  void aBundleSurvivesBeingQueued() {
    UUID claim = UUID.randomUUID();
    PdfRequest request = PdfRequest.builder("Hearing bundle")
        .fileName("hearing.pdf")
        .documentCoverSheets()
        .pageNumbers(PageNumbers.TOP_RIGHT_N)
        .missingDocuments(MissingDocuments.FAIL)
        .coverPage(HtmlPage.builder().title("Front").body("<p>Front</p>").css("p{}").build())
        .document(DocSource.bytes(new byte[] {1, 2}, "note.txt", "text/plain"))
        .section(PdfSection.builder("Claim")
            .document(PdfPart.builder(DocSource.cdam(claim, "claim.pdf"))
                .title("Claim form").date(LocalDate.of(2026, 3, 14)).confidential().build())
            .section(PdfSection.builder("Empty").placeholderWhenEmpty().build())
            .build())
        .watermark(Watermark.image(PNG).onFirstPageOnly().underContent())
        .print(PrintOptions.bulkPrint().toBuilder().colour(PrintColour.GRAYSCALE)
            .maxOutputBytes(50L * 1024 * 1024).build())
        .build();

    PdfRequest read = DocRequestJson.read(DocRequestJson.write(request));

    assertThat(read.title()).isEqualTo("Hearing bundle");
    assertThat(read.fileName()).isEqualTo("hearing.pdf");
    assertThat(List.of(read.titlePage(), read.tableOfContents(), read.sectionCoverSheets(),
        read.documentCoverSheets(), read.confidentialMarking()))
        .containsExactly(false, false, false, true, false);
    assertThat(read.pageNumbers()).isEqualTo(PageNumbers.TOP_RIGHT_N);
    assertThat(read.missingDocuments()).isEqualTo(MissingDocuments.FAIL);
    assertThat(read.coverPage()).hasValueSatisfying(cover -> {
      HtmlPage page = (HtmlPage) cover;
      assertThat(List.of(page.title(), page.body(), page.css()))
          .containsExactly("Front", "<p>Front</p>", "p{}");
    });
    assertThat(read.root().parts()).singleElement().satisfies(note ->
        assertThat(note.source()).isEqualTo(DocSource.bytes(new byte[] {1, 2}, "note.txt",
            "text/plain")));
    PdfSection section = read.root().sections().getFirst();
    assertThat(section.parts()).singleElement().satisfies(document -> {
      assertThat(document.title()).isEqualTo("Claim form");
      assertThat(document.date()).contains(LocalDate.of(2026, 3, 14));
      assertThat(document.confidential()).isTrue();
      assertThat(document.source()).isEqualTo(DocSource.cdam(claim, "claim.pdf"));
    });
    assertThat(section.sections()).singleElement().satisfies(empty ->
        assertThat(empty.placeholderWhenEmpty()).isTrue());
    assertThat(read.watermark()).contains(Watermark.image(PNG).onFirstPageOnly().underContent());
    assertThat(read.print()).contains(request.print().orElseThrow());
    assertThat(read.classification()).isEqualTo(Classification.PUBLIC);
    assertThat(read.category()).isEmpty();
  }

  @Test
  void aLetterSurvivesBeingQueued() {
    PdfRequest request = PdfRequest.builder("CPC-01")
        .document(PdfPart.builder(DocSource.bytes(new byte[] {1}, "coversheet.pdf")).unchanged()
            .build())
        .document(DocSource.cdam(UUID.randomUUID()))
        .print(PrintOptions.bulkPrint())
        .build();

    PdfRequest read = DocRequestJson.read(DocRequestJson.write(request));

    assertThat(read.allParts()).extracting(PdfPart::source)
        .isEqualTo(request.allParts().stream().map(PdfPart::source).toList());
    assertThat(read.allParts()).extracting(PdfPart::unchanged).containsExactly(true, false);
    assertThat(read.assembled()).isFalse();
    assertThat(read.title()).isEqualTo("CPC-01");
    assertThat(read.fileName()).isEqualTo(request.fileName());
    assertThat(read.print()).isEqualTo(request.print());
    assertThat(read.watermark()).isEmpty();
  }

  @Test
  void aBundleWithEveryOptionSetReadsBackToTheSameStoredForm() {
    String stored = DocRequestJson.write(everyBundleOption());

    assertThat(DocRequestJson.write(DocRequestJson.read(stored))).isEqualTo(stored);
    assertThat(stored).contains("\"category\":\"bundles\"", "\"classification\":\"PRIVATE\"",
        "\"id\":\"claim-1\"", "\"missingDocuments\":\"PLACEHOLDER\"", "\"titlePage\":true",
        "\"tableOfContents\":true", "\"sectionCoverSheets\":true",
        "\"documentCoverSheets\":true", "\"confidentialMarking\":true");
  }

  @Test
  void aLetterWithEveryOptionSetReadsBackToTheSameStoredForm() {
    String stored = DocRequestJson.write(everyLetterOption());

    assertThat(DocRequestJson.write(DocRequestJson.read(stored))).isEqualTo(stored);
    assertThat(stored).contains("\"unchanged\":true");
  }

  @Test
  void equalRequestsBuiltSeparatelyAreStoredIdentically() {
    assertThat(DocRequestJson.write(everyBundleOption()))
        .isEqualTo(DocRequestJson.write(everyBundleOption()));
    assertThat(DocRequestJson.write(everyLetterOption()))
        .isEqualTo(DocRequestJson.write(everyLetterOption()));
  }

  private static PdfRequest everyBundleOption() {
    return PdfRequest.builder("Hearing bundle")
        .fileName("hearing.pdf")
        .coverPage(HtmlPage.builder().title("Front").body("<p>Front</p>").css("p{}").build())
        .titlePage()
        .tableOfContents()
        .sectionCoverSheets()
        .documentCoverSheets()
        .pageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M)
        .confidentialMarking()
        .watermark(Watermark.image(PNG).onFirstPageOnly().underContent())
        .missingDocuments(MissingDocuments.PLACEHOLDER)
        .print(PrintOptions.bulkPrint().toBuilder().colour(PrintColour.GRAYSCALE).build())
        .category("bundles")
        .classification(Classification.PRIVATE)
        .document(DocSource.bytes(new byte[] {1, 2}, "note.txt", "text/plain"))
        .section(PdfSection.builder("Claim")
            .document(PdfPart.builder(DocSource.cdam(CLAIM, "claim.pdf")).id("claim-1")
                .title("Claim form").date(LocalDate.of(2026, 3, 14)).confidential().build())
            .document(DocSource.cdam(ORDER))
            .document(HtmlPage.builder().title("Notes").body("<p>Notes</p>").build())
            .section(PdfSection.builder("Empty").placeholderWhenEmpty().build())
            .build())
        .build();
  }

  private static PdfRequest everyLetterOption() {
    return PdfRequest.builder("CPC-01")
        .fileName("letter.pdf")
        .document(PdfPart.builder(DocSource.bytes(new byte[] {1}, "coversheet.pdf")).id("cover")
            .title("Coversheet").unchanged().build())
        .document(PdfPart.builder(DocSource.cdam(CLAIM)).date(LocalDate.of(2026, 1, 2))
            .confidential().build())
        .watermark(Watermark.image(PNG))
        .print(PrintOptions.bulkPrint())
        .category("letters")
        .classification(Classification.RESTRICTED)
        .build();
  }

  @Test
  void aResultSurvivesBeingStored() {
    UploadedDoc uploaded = new UploadedDoc(Document.builder().url("http://cdam/documents/1")
        .binaryUrl("http://cdam/documents/1/binary").filename("bundle.pdf").categoryId("bundles")
        .uploadTimestamp(LocalDateTime.of(2026, 11, 24, 9, 30)).build(), 12,
        List.of(new DocFailure("Gone", DocFailure.Reason.NOT_FOUND, "404"),
            new DocFailure(Optional.of("statement-42"), "Statement",
                List.of("Hearing bundle", "Evidence"), OptionalInt.of(7),
                DocFailure.Reason.ACCESS_DENIED, "403")));

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
