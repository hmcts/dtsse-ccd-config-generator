package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.folder;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.request;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.tocOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;

/**
 * Ports the scenarios of em-stitching's {@code PDFMergerCoversheetsTest}: folder cover sheets on
 * and off, multiple and nested folders, document cover sheets, and empty folders — plus the SDK's
 * empty-expected-section placeholder page.
 */
class PdfBundleAssemblerCoversheetsTest {

  @TempDir
  Path tmp;

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();
  private Path workDir;
  private Path file1;
  private Path annotationTemplate;
  private int annotationPages;
  private BundlePresentation withFolderCoversheets;

  @BeforeEach
  void setUp() {
    workDir = tmp.resolve("work");
    file1 = textPdf(tmp, "Title of the bundle", 2);
    annotationTemplate = fixture("annotationTemplate.pdf");
    annotationPages = Pdfs.pageCount(annotationTemplate);
    withFolderCoversheets = tocOnly().withSectionCoverSheets(true);
  }

  @Test
  void folderCoverSheetIsRenderedAndListedInTheContents() throws IOException {
    AssemblyResult result = assembler.assemble(request(withFolderCoversheets,
        folder("Folder 1", doc("Bundle Doc 1", file1)),
        doc("Bundle Doc 2", annotationTemplate)), workDir);

    assertThat(result.totalPages()).isEqualTo(1 + 1 + 2 + annotationPages);
    // Once in the contents, once on the cover sheet.
    assertThat(Pdfs.count(Pdfs.allText(result.outputPdf()), "Folder 1")).isEqualTo(2);
    String coverSheetText = Pdfs.pageText(result.outputPdf(), 2);
    assertThat(coverSheetText).contains("Folder 1").contains("Back to index");
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(), 2)).containsExactly(1);
  }

  @Test
  void folderCoverSheetsToggleOff() throws IOException {
    AssemblyResult result = assembler.assemble(request(tocOnly(),
        folder("Folder 1", doc("Bundle Doc 1", file1)),
        doc("Bundle Doc 2", annotationTemplate)), workDir);

    assertThat(result.totalPages()).isEqualTo(1 + 2 + annotationPages);
    // Without folder cover sheets the folder contributes no page, contents line or bookmark.
    assertThat(Pdfs.allText(result.outputPdf())).doesNotContain("Folder 1");
    assertThat(Pdfs.outline(result.outputPdf())).noneMatch(entry -> entry.contains("Folder 1"));
  }

  @Test
  void multipleFolderCoverSheets() throws IOException {
    AssemblyResult result = assembler.assemble(request(withFolderCoversheets,
        folder("Folder 1", doc("Bundle Doc 1", file1)),
        folder("Folder 2", doc("Bundle Doc 2", annotationTemplate))), workDir);

    assertThat(result.totalPages()).isEqualTo(1 + 2 + 2 + annotationPages);
    String tocText = Pdfs.pageText(result.outputPdf(), 1);
    assertThat(Pdfs.count(tocText, "Folder 1")).isEqualTo(1);
    assertThat(Pdfs.count(tocText, "Folder 2")).isEqualTo(1);
  }

  @Test
  void multipleFolderCoverSheetsWithDocumentCoverSheets() throws IOException {
    var presentation = withFolderCoversheets.withDocumentCoverSheets(true);

    AssemblyResult result = assembler.assemble(request(presentation,
        folder("Folder 1", doc("Bundle Doc 1", file1)),
        folder("Folder 2", doc("Bundle Doc 2", annotationTemplate))), workDir);

    assertThat(result.totalPages()).isEqualTo(1 + 2 + 2 + 2 + annotationPages);
    String allText = Pdfs.allText(result.outputPdf());
    assertThat(Pdfs.count(allText, "Folder 1")).isEqualTo(2);
    assertThat(Pdfs.count(allText, "Folder 2")).isEqualTo(2);
    assertThat(allText.indexOf("Folder 1")).isLessThan(allText.indexOf("Folder 2"));
  }

  @Test
  void nestedSubfolderCoverSheets() throws IOException {
    AssemblyResult result = assembler.assemble(request(withFolderCoversheets,
        folder("Folder 1",
            doc("Doc inside folder", file1),
            folder("Folder 2", doc("Doc inside subfolder", annotationTemplate)))), workDir);

    assertThat(result.totalPages()).isEqualTo(1 + 2 + 2 + annotationPages);
    assertThat(Pdfs.count(Pdfs.pageText(result.outputPdf(), 1), "Folder 1")).isEqualTo(1);
    // Bookmarks mirror the nesting: subfolder and its document sit beneath the parent folder.
    assertThat(Pdfs.outline(result.outputPdf())).containsSubsequence(
        "  Folder 1 -> 2",
        "    Doc inside folder -> 3",
        "    Folder 2 -> 5",
        "      Doc inside subfolder -> 6");
  }

  @Test
  void emptyFoldersAreSkippedEntirely() throws IOException {
    AssemblyResult result = assembler.assemble(request(withFolderCoversheets,
        folder("Empty Folder"),
        doc("Bundle Doc 2", annotationTemplate)), workDir);

    assertThat(result.totalPages()).isEqualTo(1 + annotationPages);
    assertThat(Pdfs.allText(result.outputPdf())).doesNotContain("Empty Folder");
    assertThat(Pdfs.outline(result.outputPdf()))
        .noneMatch(entry -> entry.contains("Empty Folder"));
  }

  @Test
  void emptySectionPlaceholderPageIsRenderedListedAndWarned() throws IOException {
    AssemblyItem placeholder = new AssemblyItem(
        "Applications", Optional.empty(), false, new EmptySectionPage());

    AssemblyResult result = assembler.assemble(request(withFolderCoversheets,
        folder("Applications", placeholder),
        doc("Bundle Doc 2", annotationTemplate)), workDir);

    // Pages: contents, folder cover sheet, placeholder page, then the document.
    assertThat(result.totalPages()).isEqualTo(1 + 1 + 1 + annotationPages);
    String placeholderText = Pdfs.pageText(result.outputPdf(), 3);
    assertThat(placeholderText)
        .contains("Applications")
        .contains("There are no documents in this section.");
    assertThat(Pdfs.pageText(result.outputPdf(), 1)).contains("Applications");
    assertThat(result.items()).contains(new AssembledItem("Applications", 3, 1));
    assertThat(result.warnings()).anySatisfy(warning -> {
      assertThat(warning.code()).isEqualTo(PdfBundleAssembler.WARNING_EMPTY_SECTION_PAGE);
      assertThat(warning.documentId()).contains("Applications");
    });
  }
}
