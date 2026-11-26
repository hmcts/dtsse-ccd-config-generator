package uk.gov.hmcts.ccd.sdk.doctoolkit.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.doctoolkit.Classification;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.HtmlPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.MissingDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PageNumbers;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfPart;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfSection;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PrintColour;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PrintOptions;
import uk.gov.hmcts.ccd.sdk.doctoolkit.Watermark;

class DocRequestJsonTest {
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
  private static final UUID CLAIM = UUID.fromString("6f1c3c38-3d0e-4b8e-9a4b-4a3f0e2c1d10");
  private static final UUID ORDER = UUID.fromString("0b6a1f5e-8c2d-4e7f-a1b2-c3d4e5f60718");

  @Test
  void aBundleWithEveryOptionSetSurvivesBeingQueued() {
    assertSurvivesBeingQueued(everyBundleOption());
  }

  @Test
  void aLetterWithEveryOptionSetSurvivesBeingQueued() {
    assertSurvivesBeingQueued(everyLetterOption());
  }

  /** Reads back as the request it was, and is stored the same way again. */
  private static void assertSurvivesBeingQueued(PdfRequest request) {
    String stored = DocRequestJson.write(request);
    PdfRequest read = DocRequestJson.read(stored);

    assertThat(read).usingRecursiveComparison().isEqualTo(request);
    assertThat(DocRequestJson.write(read)).isEqualTo(stored);
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
  void anUnknownStoredFormIsUnreadable() {
    assertThatThrownBy(() -> DocRequestJson.read("{\"type\":\"spreadsheet\"}"))
        .isInstanceOf(DocRequestJson.Unreadable.class);
    assertThatThrownBy(() -> DocRequestJson.read("not json"))
        .isInstanceOf(DocRequestJson.Unreadable.class);
  }
}
