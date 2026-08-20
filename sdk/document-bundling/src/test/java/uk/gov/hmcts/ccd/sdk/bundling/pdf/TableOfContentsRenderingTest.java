package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.requestWithDescription;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.tocOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ports the scenarios of em-stitching's {@code TableOfContentsTest} that describe visible output:
 * the heading block (description, centred "Index Page" title, column headers), document entries
 * with clickable links, dates and start pages, and entry flow across pages.
 */
class TableOfContentsRenderingTest {

  private static final String DESCRIPTION =
      "This is the description, it should really be wrapped but it is not currently."
          + " The table limit is 255 characters anyway.";

  @TempDir
  Path tmp;

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();
  private Path workDir;
  private Path file1;

  @BeforeEach
  void setUp() {
    workDir = tmp.resolve("work");
    file1 = textPdf(tmp, "Title of the bundle", 2);
  }

  @Test
  void headingShowsDescriptionTitleAndColumnHeaders() throws IOException {
    AssemblyResult result = assembler.assemble(requestWithDescription(tocOnly(), DESCRIPTION,
        doc("Bundle Doc 1", file1)), workDir);

    String tocText = Pdfs.pageText(result.outputPdf(), 1);
    assertThat(tocText)
        .contains("This is the description")
        .contains("Index Page")
        .contains("Date")
        .contains("Page");
  }

  @Test
  void entriesShowTitleDateAndStartPageAndLinkToTheDocument() throws IOException {
    AssemblyResult result = assembler.assemble(requestWithDescription(tocOnly(), DESCRIPTION,
        doc("Bundle Doc 1", LocalDate.of(2024, 1, 12), file1),
        doc("Bundle Doc 2", LocalDate.of(2024, 3, 2), file1)), workDir);

    String tocText = Pdfs.pageText(result.outputPdf(), 1);
    assertThat(tocText)
        .contains("Bundle Doc 1")
        .contains("12 Jan 2024")
        .contains("Bundle Doc 2")
        .contains("2 Mar 2024");
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(), 1)).containsExactly(2, 4);
    assertThat(result.items()).containsExactly(
        new AssembledItem("Bundle Doc 1", 2, 2),
        new AssembledItem("Bundle Doc 2", 4, 2));
  }

  @Test
  void entryWithoutDateLeavesTheDateColumnEmpty() throws IOException {
    AssemblyResult result = assembler.assemble(requestWithDescription(tocOnly(), DESCRIPTION,
        doc("Bundle Doc 1", file1)), workDir);

    assertThat(Pdfs.pageText(result.outputPdf(), 1)).doesNotContain("Jan");
  }

  @Test
  void unicodeOutsideWinAnsiIsSanitisedFromDrawnText() throws IOException {
    AssemblyResult result = assembler.assemble(requestWithDescription(tocOnly(), DESCRIPTION,
        doc("Report ąćę – bullet •", file1)), workDir);

    // The characters WinAnsi cannot encode are dropped from the drawn contents line; the rest of
    // the title survives.
    String tocText = Pdfs.pageText(result.outputPdf(), 1);
    assertThat(tocText).contains("Report");
    assertThat(tocText).doesNotContain("ą").doesNotContain("•");
  }

  @Test
  void entriesFlowAcrossPagesInOrder() throws IOException {
    var items = new java.util.ArrayList<AssemblyNode>();
    for (int i = 1; i <= 80; i++) {
      items.add(doc("Bundle Doc " + i, textPdf(tmp, "d" + i, 1)));
    }

    AssemblyResult result = assembler.assemble(AssemblyRequest.of("Title of the bundle",
        "stitched.pdf", tocOnly(), items), workDir);

    // 6 heading lines + 80 entries = 86 lines -> 3 contents pages.
    assertThat(result.totalPages()).isEqualTo(3 + 80);
    String page1 = Pdfs.pageText(result.outputPdf(), 1);
    String page2 = Pdfs.pageText(result.outputPdf(), 2);
    String page3 = Pdfs.pageText(result.outputPdf(), 3);
    assertThat(page1).contains("Bundle Doc 32").doesNotContain("Bundle Doc 33");
    assertThat(page2).contains("Bundle Doc 33").contains("Bundle Doc 70");
    assertThat(page3).contains("Bundle Doc 71").contains("Bundle Doc 80");
  }
}
