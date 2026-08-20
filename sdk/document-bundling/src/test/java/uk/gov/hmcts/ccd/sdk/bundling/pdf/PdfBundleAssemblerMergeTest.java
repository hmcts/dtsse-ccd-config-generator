package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.folder;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.plain;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.request;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.tocOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ports the scenarios of em-stitching's {@code PDFMergerTest} against the SDK assembly model:
 * merging with and without a table of contents, multiline titles, multi-page tables of contents,
 * failure messages naming the document, special characters, long titles, and the never-mutate-
 * sources and preserve-dimensions constraints.
 */
class PdfBundleAssemblerMergeTest {

  @TempDir
  Path tmp;

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();
  private Path workDir;
  private Path file1;
  private Path annotationTemplate;

  @BeforeEach
  void setUp() {
    workDir = tmp.resolve("work");
    file1 = textPdf(tmp, "Title of the bundle", 2);
    annotationTemplate = fixture("annotationTemplate.pdf");
  }

  @Test
  void mergeWithTableOfContents() throws IOException {
    int annotationPages = Pdfs.pageCount(annotationTemplate);

    AssemblyResult result = assembler.assemble(
        request(tocOnly(), doc("Bundle Doc 1", file1), doc("Bundle Doc 2", annotationTemplate)),
        workDir);

    assertThat(result.totalPages()).isEqualTo(2 + annotationPages + 1);
    assertThat(Pdfs.pageCount(result.outputPdf())).isEqualTo(result.totalPages());
    assertThat(result.items()).containsExactly(
        new AssembledItem("Bundle Doc 1", 2, 2),
        new AssembledItem("Bundle Doc 2", 4, annotationPages));
    String tocText = Pdfs.pageText(result.outputPdf(), 1);
    assertThat(tocText).contains("Index Page", "Bundle Doc 1", "Bundle Doc 2");
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(), 1)).containsExactly(2, 4);
  }

  @Test
  void mergeWithoutTableOfContents() throws IOException {
    int annotationPages = Pdfs.pageCount(annotationTemplate);

    AssemblyResult result = assembler.assemble(
        request(plain(), doc("Bundle Doc 1", file1), doc("Bundle Doc 2", annotationTemplate)),
        workDir);

    assertThat(result.totalPages()).isEqualTo(2 + annotationPages);
    assertThat(Pdfs.allText(result.outputPdf())).doesNotContain("Index Page");
    assertThat(result.items()).containsExactly(
        new AssembledItem("Bundle Doc 1", 1, 2),
        new AssembledItem("Bundle Doc 2", 3, annotationPages));
  }

  @Test
  void mergeWithoutTableOfContentsAddsNoTextOfItsOwn() throws IOException {
    AssemblyResult result = assembler.assemble(
        request(plain(), doc("Bundle Doc 1", file1), doc("Bundle Doc 2", file1)), workDir);

    // Every occurrence of the marker text comes from the sources; the merge adds none.
    String text = Pdfs.allText(result.outputPdf());
    int perSource = Pdfs.count(Pdfs.allText(file1), "Title of the bundle");
    assertThat(Pdfs.count(text, "Title of the bundle")).isEqualTo(2 * perSource);
  }

  @Test
  void mergeWithTitlePage() throws IOException {
    AssemblyRequest withTitlePage = new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.of("A hearing bundle"), tocOnly(), true, Optional.empty(),
        TestPdfs.nodes(doc("Bundle Doc 1", file1)));

    AssemblyResult result = assembler.assemble(withTitlePage, workDir);

    assertThat(result.totalPages()).isEqualTo(1 + 1 + 2);
    assertThat(Pdfs.pageText(result.outputPdf(), 1))
        .contains("Title of the bundle")
        .contains("A hearing bundle");
    assertThat(Pdfs.outline(result.outputPdf()))
        .contains("  " + PdfBundleAssembler.TITLE_PAGE_BOOKMARK + " -> 1");
    // The table of contents follows the title page and the document follows the contents.
    assertThat(result.items()).containsExactly(new AssembledItem("Bundle Doc 1", 3, 2));
  }

  @Test
  void mergeWithTableOfContentsWithMultilineTitles() throws IOException {
    String longTitle1 = "Bundle Doc 1 " + "Very long ".repeat(30).trim();
    String longTitle2 = "Bundle Doc 2 " + "Very long ".repeat(30).trim();

    AssemblyResult result = assembler.assemble(
        request(tocOnly(), doc(longTitle1, file1), doc(longTitle2, annotationTemplate)), workDir);

    int contentPages = 2 + Pdfs.pageCount(annotationTemplate);
    int tocPages = result.totalPages() - contentPages;
    assertThat(tocPages).isEqualTo(1);
    assertThat(result.items().get(0).startPage()).isEqualTo(tocPages + 1);
    // Both wrapped titles are fully present in the contents.
    String normalised = Pdfs.normalisedText(result.outputPdf());
    assertThat(Pdfs.count(normalised, longTitle1)).isEqualTo(1);
    assertThat(Pdfs.count(normalised, longTitle2)).isEqualTo(1);
  }

  @Test
  void multiPageTableOfContents() throws IOException {
    Path onePage = fixture("one-page.pdf");
    List<AssemblyNode> items = new ArrayList<>();
    for (int i = 1; i <= 100; i++) {
      items.add(doc("Bundle Doc " + i, onePage));
    }

    AssemblyResult result = assembler.assemble(
        AssemblyRequest.of("Title of the bundle", "stitched.pdf", tocOnly(), items), workDir);

    // 6 heading lines plus 100 single-line entries need 3 index pages of 38 lines.
    assertThat(result.totalPages()).isEqualTo(3 + 100);
    assertThat(result.items().get(0).startPage()).isEqualTo(4);
    assertThat(Pdfs.pageText(result.outputPdf(), 3)).contains("Bundle Doc 100");
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(), 3)).isNotEmpty();
    assertThat(result.items().get(99))
        .isEqualTo(new AssembledItem("Bundle Doc 100", 103, 1));
  }

  @Test
  void multiPageTableOfContentsWithFolders() throws IOException {
    Path potentialEnergy = fixture("Potential_Energy_PDF.pdf");
    int potentialEnergyPages = Pdfs.pageCount(potentialEnergy);
    List<AssemblyNode> items = new ArrayList<>();
    int documentNumber = 0;
    for (int i = 0; i < 4; i++) {
      List<AssemblyNode> children = new ArrayList<>();
      for (int j = 0; j < 10; j++) {
        children.add(doc("Bundle Doc " + documentNumber++, potentialEnergy));
      }
      items.add(new AssemblyFolder("Folder " + i, children));
    }
    var presentation = tocOnly().withSectionCoverSheets(true);

    AssemblyResult result = assembler.assemble(
        AssemblyRequest.of("Title of the bundle", "stitched.pdf", presentation, items), workDir);

    // 6 heading lines plus, per folder, 3 folder lines and 10 entries: 58 lines, 2 index pages.
    int expectedTocPages = 2;
    int expectedPages = expectedTocPages + 4 + (40 * potentialEnergyPages);
    assertThat(result.totalPages()).isEqualTo(expectedPages);
    assertThat(result.items()).hasSize(40);
  }

  @Test
  void addsSpaceInContentsAfterEndOfFolder() throws IOException {
    var presentation = tocOnly().withSectionCoverSheets(true);

    AssemblyResult result = assembler.assemble(request(presentation,
        doc("Bundle Doc 1", file1),
        folder("Folder 1", doc("Folder Doc 1", file1)),
        doc("Bundle Doc 2", file1)), workDir);

    // Contents lines: 6 heading + 1 + 3 (folder) + 1 + 1 (space after folder) + 1 = 13 -> 1 page.
    // Pages: contents, doc1 (2), folder cover sheet, folder doc (2), doc2 (2).
    assertThat(result.totalPages()).isEqualTo(8);
    assertThat(result.items()).containsExactly(
        new AssembledItem("Bundle Doc 1", 2, 2),
        new AssembledItem("Folder Doc 1", 5, 2),
        new AssembledItem("Bundle Doc 2", 7, 2));
  }

  @Test
  void corruptSourceFailsWithAnErrorNamingTheDocument() {
    Path notaPdf = fixture("TestExcelConversion.xlsx");

    assertThatThrownBy(() -> assembler.assemble(
        request(tocOnly(), doc("Bundle Doc 1", notaPdf)), workDir))
        .isInstanceOf(IOException.class)
        .hasMessage(
            "Error processing, document title: Bundle Doc 1, file name: TestExcelConversion.xlsx");
  }

  @Test
  void tableOfContentsWithoutDescriptionStillRendersHeading() throws IOException {
    AssemblyResult result = assembler.assemble(
        request(tocOnly(), doc("Bundle Doc 1", file1)), workDir);

    assertThat(result.totalPages()).isEqualTo(1 + 2);
    assertThat(Pdfs.pageText(result.outputPdf(), 1)).contains("Index Page");
  }

  @Test
  void specialCharactersArePreservedInBookmarks() throws IOException {
    AssemblyRequest specialTitle = AssemblyRequest.of("ąćęłńóśźż", "stitched.pdf", tocOnly(),
        TestPdfs.nodes(doc("ąćęłńóśźż", fixture("Potential_Energy_PDF.pdf"))));

    AssemblyResult result = assembler.assemble(specialTitle, workDir);

    assertThat(Pdfs.outline(result.outputPdf()).get(0)).isEqualTo("ąćęłńóśźż -> 1");
  }

  @Test
  void longDocumentTitleAppearsInContentsAndOnCoverSheet() throws IOException {
    String longTitle = "DocName ".repeat(20).trim();
    var presentation = tocOnly().withDocumentCoverSheets(true);

    AssemblyResult result = assembler.assemble(request(presentation,
        doc(longTitle, file1), doc("Bundle Doc 2", annotationTemplate)), workDir);

    String normalised = Pdfs.normalisedText(result.outputPdf());
    assertThat(Pdfs.count(normalised, longTitle)).isEqualTo(2);
  }

  @Test
  void preservesSourcePageDimensionsAndRotations() throws IOException {
    Path rotated = tmp.resolve("rotated.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage a4Rotated = new PDPage(PDRectangle.A4);
      a4Rotated.setRotation(90);
      document.addPage(a4Rotated);
      PDPage custom = new PDPage(new PDRectangle(200, 400));
      document.addPage(custom);
      try (PDPageContentStream contents = new PDPageContentStream(document, a4Rotated)) {
        contents.beginText();
        contents.setFont(new PdfFonts().helvetica(), 12);
        contents.newLineAtOffset(50, 50);
        contents.showText("rotated");
        contents.endText();
      }
      document.save(rotated.toFile());
    }

    AssemblyResult result = assembler.assemble(
        request(plain(), doc("Rotated Doc", rotated)), workDir);

    try (PDDocument merged = Loader.loadPDF(result.outputPdf().toFile())) {
      assertThat(merged.getPage(0).getRotation()).isEqualTo(90);
      assertThat(merged.getPage(0).getMediaBox().getWidth())
          .isEqualTo(PDRectangle.A4.getWidth());
      assertThat(merged.getPage(0).getMediaBox().getHeight())
          .isEqualTo(PDRectangle.A4.getHeight());
      assertThat(merged.getPage(1).getMediaBox().getWidth()).isEqualTo(200);
      assertThat(merged.getPage(1).getMediaBox().getHeight()).isEqualTo(400);
    }
  }

  @Test
  void neverMutatesSourceFiles() throws IOException {
    String file1Before = TestPdfs.sha256(file1);
    String annotationBefore = TestPdfs.sha256(annotationTemplate);

    assembler.assemble(
        request(tocOnly(), doc("Bundle Doc 1", file1), doc("Bundle Doc 2", annotationTemplate)),
        workDir);

    assertThat(TestPdfs.sha256(file1)).isEqualTo(file1Before);
    assertThat(TestPdfs.sha256(annotationTemplate)).isEqualTo(annotationBefore);
  }

  @Test
  void outputAndScratchFilesStayUnderTheWorkingDirectory() throws IOException {
    AssemblyResult result = assembler.assemble(
        request(tocOnly(), doc("Bundle Doc 1", file1)), workDir);

    assertThat(result.outputPdf()).isEqualTo(workDir.resolve("stitched.pdf"));
    assertThat(result.outputPdf().normalize()).startsWith(workDir);
  }

  @Test
  void requestWithNoRenderableItemsIsRejected() {
    AssemblyRequest empty = request(tocOnly(), folder("Empty Folder"));

    assertThatThrownBy(() -> assembler.assemble(empty, workDir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no renderable items");
  }
}
