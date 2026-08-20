package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.confidentialDoc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.plain;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.request;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.tocOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

/**
 * Ports em-stitching's pagination scenarios (numbers printed on document pages only, absolute
 * position numbering, pagination off) and adds the SDK's {@code N of M} variants and the approved
 * confidential header marking.
 */
class PdfBundleAssemblerPaginationTest {

  @TempDir
  Path tmp;

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();
  private Path workDir;
  private Path twoPageDoc;

  @BeforeEach
  void setUp() {
    workDir = tmp.resolve("work");
    twoPageDoc = textPdf(tmp, "DOC", 2);
  }

  @Test
  void pageNumbersArePrintedOnDocumentPagesButNotTheContentsPage() throws IOException {
    var presentation = tocOnly().withPageNumbers(PageNumbers.BOTTOM_RIGHT_N);

    AssemblyResult result = assembler.assemble(request(presentation,
        doc("Document 1", twoPageDoc),
        doc("Document 2", twoPageDoc),
        doc("Document 3", twoPageDoc)), workDir);

    assertThat(result.totalPages()).isEqualTo(7);
    assertThat(hasStandaloneNumber(Pdfs.pageText(result.outputPdf(), 1), 1)).isFalse();
    for (int page = 2; page <= 7; page++) {
      assertThat(hasStandaloneNumber(Pdfs.pageText(result.outputPdf(), page), page))
          .as("page %d should be stamped with its absolute position", page)
          .isTrue();
    }
  }

  @Test
  void nofMVariantsPrintTheBundleTotal() throws IOException {
    var presentation = tocOnly().withPageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M);

    AssemblyResult result = assembler.assemble(request(presentation,
        doc("Document 1", twoPageDoc),
        doc("Document 2", twoPageDoc)), workDir);

    assertThat(result.totalPages()).isEqualTo(5);
    assertThat(Pdfs.pageText(result.outputPdf(), 2)).contains("2 of 5");
    assertThat(Pdfs.pageText(result.outputPdf(), 5)).contains("5 of 5");
    assertThat(Pdfs.pageText(result.outputPdf(), 1)).doesNotContain("of 5");
  }

  @Test
  void topRightPresetStampsEveryDocumentPage() throws IOException {
    var presentation = tocOnly().withPageNumbers(PageNumbers.TOP_RIGHT_N_OF_M);

    AssemblyResult result = assembler.assemble(
        request(presentation, doc("Document 1", twoPageDoc)), workDir);

    assertThat(Pdfs.pageText(result.outputPdf(), 2)).contains("2 of 3");
    assertThat(Pdfs.pageText(result.outputPdf(), 3)).contains("3 of 3");
  }

  @Test
  void paginationOffPrintsNoNumbers() throws IOException {
    var presentation = tocOnly().withPageNumbers(PageNumbers.NONE);

    AssemblyResult result = assembler.assemble(request(presentation,
        doc("Document 1", twoPageDoc),
        doc("Document 2", twoPageDoc)), workDir);

    for (int page = 1; page <= result.totalPages(); page++) {
      assertThat(hasStandaloneNumber(Pdfs.pageText(result.outputPdf(), page), page))
          .as("page %d should carry no stamp when pagination is off", page)
          .isFalse();
    }
  }

  @Test
  void coverSheetsAreNotPaginated() throws IOException {
    var presentation = plain()
        .withDocumentCoverSheets(true)
        .withPageNumbers(PageNumbers.BOTTOM_RIGHT_N);

    AssemblyResult result = assembler.assemble(request(presentation,
        doc("Document 1", twoPageDoc),
        doc("Document 2", twoPageDoc)), workDir);

    // Pages: cover, 2-3 content, cover, 5-6 content.
    assertThat(result.totalPages()).isEqualTo(6);
    assertThat(hasStandaloneNumber(Pdfs.pageText(result.outputPdf(), 1), 1)).isFalse();
    assertThat(hasStandaloneNumber(Pdfs.pageText(result.outputPdf(), 4), 4)).isFalse();
    for (int page : new int[] {2, 3, 5, 6}) {
      assertThat(hasStandaloneNumber(Pdfs.pageText(result.outputPdf(), page), page)).isTrue();
    }
  }

  @Test
  void confidentialItemsGetTheApprovedHeaderOnEveryPage() throws IOException {
    var presentation = tocOnly().withConfidentialMarking(ConfidentialMarking.APPROVED_HEADER);

    AssemblyResult result = assembler.assemble(request(presentation,
        confidentialDoc("Secret Doc", twoPageDoc),
        doc("Open Doc", twoPageDoc)), workDir);

    assertThat(Pdfs.pageText(result.outputPdf(), 1)).doesNotContain("CONFIDENTIAL");
    assertThat(Pdfs.pageText(result.outputPdf(), 2)).contains("CONFIDENTIAL");
    assertThat(Pdfs.pageText(result.outputPdf(), 3)).contains("CONFIDENTIAL");
    assertThat(Pdfs.pageText(result.outputPdf(), 4)).doesNotContain("CONFIDENTIAL");
    assertThat(Pdfs.pageText(result.outputPdf(), 5)).doesNotContain("CONFIDENTIAL");
  }

  @Test
  void confidentialFlagIsIgnoredWhenMarkingIsNone() throws IOException {
    AssemblyResult result = assembler.assemble(request(tocOnly(),
        confidentialDoc("Secret Doc", twoPageDoc)), workDir);

    assertThat(Pdfs.allText(result.outputPdf())).doesNotContain("CONFIDENTIAL");
  }

  @Test
  void confidentialMarkingCoversTheItemsCoverSheet() throws IOException {
    var presentation = tocOnly()
        .withDocumentCoverSheets(true)
        .withConfidentialMarking(ConfidentialMarking.APPROVED_HEADER);

    AssemblyResult result = assembler.assemble(request(presentation,
        confidentialDoc("Secret Doc", twoPageDoc)), workDir);

    // Pages: contents, cover sheet, 2 content pages.
    assertThat(Pdfs.pageText(result.outputPdf(), 2)).contains("CONFIDENTIAL");
    assertThat(Pdfs.pageText(result.outputPdf(), 3)).contains("CONFIDENTIAL");
    assertThat(Pdfs.pageText(result.outputPdf(), 4)).contains("CONFIDENTIAL");
  }

  private static boolean hasStandaloneNumber(String pageText, int number) {
    return Pattern.compile("(?m)^" + number + "$").matcher(pageText).find();
  }
}
