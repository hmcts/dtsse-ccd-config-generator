package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.described;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.folder;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.request;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.Pdfs.tocOnly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

class PdfBundleAssemblerTest {
  @TempDir
  Path tmp;

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();
  private Path workDir;
  private Path twoPage;

  @BeforeEach
  void setUp() {
    workDir = tmp.resolve("work");
    twoPage = textPdf(tmp, "evidence", 2);
  }

  @Test
  void mergeWithTitlePageAndTocLinksResolveToTheRightPages() throws IOException {
    Path annotation = fixture("annotationTemplate.pdf");
    int annotationPages = Pdfs.read(annotation, PDDocument::getNumberOfPages);
    AssemblyResult result = assembler.assemble(described(tocOnly(), true, List.of(
        doc("Bundle Doc 1", LocalDate.of(2024, 1, 12), twoPage),
        doc("Bundle Doc 2", LocalDate.of(2024, 3, 2), annotation))), workDir);
    Path pdf = result.outputPdf();

    assertThat(pdf).isEqualTo(workDir.resolve("stitched.pdf"));
    assertThat(result.totalPages()).isEqualTo(2 + 2 + annotationPages)
        .isEqualTo(Pdfs.read(pdf, PDDocument::getNumberOfPages));
    assertThat(Pdfs.pageText(pdf, 1)).contains("Title of the bundle", "description").doesNotContain("Index");
    assertThat(Pdfs.pageText(pdf, 2)).contains("This is the description", "Index Page", "Date", "Page",
        "Bundle Doc 1", "12 Jan 2024", "Bundle Doc 2", "2 Mar 2024");
    assertThat(Pdfs.internalLinkTargets(pdf, 2)).containsExactly(3, 5);
    assertThat(result.items()).containsExactly(new AssembledItem("Bundle Doc 1", 3, 2),
        new AssembledItem("Bundle Doc 2", 5, annotationPages));
    assertThat(Pdfs.outline(pdf)).filteredOn(entry -> !entry.startsWith("    ")).containsExactly(
        "Title of the bundle -> 1", "  " + PdfBundleAssembler.TITLE_PAGE_BOOKMARK + " -> 1",
        "  " + TocRenderer.INDEX_PAGE + " -> 2", "  Bundle Doc 1 -> 3", "  Bundle Doc 2 -> 5");
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void nofMVariantsAndTopRightPresetPrintTheBundleTotalAndCoverSheetsAreNotPaginated()
      throws IOException {
    for (PageNumbers preset : Arrays.stream(PageNumbers.values()).filter(p -> p.name().endsWith("_OF_M")).toList()) {
      AssemblyResult result = assembler.assemble(request(
          tocOnly().withSectionCoverSheets(true).withDocumentCoverSheets(true).withPageNumbers(preset),
          folder("Folder", doc("Document 1", twoPage)), doc("Document 2", twoPage)), tmp.resolve("work-" + preset));
      Path pdf = result.outputPdf();

      // Pages: index, folder cover, doc cover, 4-5 content, doc cover, 7-8 content.
      assertThat(result.totalPages()).as("%s", preset).isEqualTo(8);
      for (int page : new int[] {1, 2, 3, 6}) {
        assertThat(Pdfs.pageText(pdf, page)).as("%s: page %d is unstamped", preset, page).doesNotContain(" of 8");
      }
      boolean top = preset == PageNumbers.TOP_RIGHT_N_OF_M;
      for (int page : new int[] {4, 5, 7, 8}) {
        assertThat(Pdfs.pageText(pdf, page)).as("%s: page %d", preset, page).contains(page + " of 8");
        assertThat(baseline(pdf, page, String.valueOf(page))).as("%s: stamp edge on page %d", preset, page)
            .isBetween(top ? 0f : 700f, top ? 60f : PDRectangle.LETTER.getHeight());
      }
    }
  }

  @Test
  void confidentialHeaderCoversOnlyTheConfidentialDocumentAndStaysInsideTheCropBox()
      throws IOException {
    Path cropped = Pdfs.pdf(tmp, "cropped-", document -> {
      PDPage page = new PDPage(new PDRectangle(612, 792));
      page.setCropBox(new PDRectangle(100, 100, 400, 500)); // visible y: 100..600
      document.addPage(page);
    });
    AssemblyItem sealed = new AssemblyItem("Sealed Doc", Optional.empty(), true, new PdfSource(cropped));
    AssemblyResult result = assembler.assemble(request(tocOnly().withDocumentCoverSheets(true)
        .withConfidentialMarking(ConfidentialMarking.APPROVED_HEADER), doc("Open Doc", twoPage), sealed), workDir);
    Path pdf = result.outputPdf();

    // Pages: index, open cover + 2 pages (2..4), sealed cover (5) + cropped page (6).
    assertThat(result.totalPages()).isEqualTo(6);
    for (int page = 1; page <= 6; page++) {
      assertThat(Pdfs.pageText(pdf, page).contains("CONFIDENTIAL")).as("page %d", page).isEqualTo(page >= 5);
    }
    // Text y is measured from the crop box top: the baseline must sit in the visible 100..600 band, not above it.
    assertThat(600 - baseline(pdf, 6, "CONFIDENTIAL")).isBetween(100f, 600f);
    AssemblyResult unmarked = assembler.assemble(request(tocOnly(), sealed), tmp.resolve("unmarked"));
    assertThat(Pdfs.pageText(unmarked.outputPdf(), 1, 2)).doesNotContain("CONFIDENTIAL");
  }

  @Test
  void neverMutatesSourceFilesAndPreservesPageDimensionsRotationsAndInternalLinks() throws IOException {
    Path source = Pdfs.pdf(tmp, "source-", document -> {
      PDPage a4Rotated = new PDPage(PDRectangle.A4);
      a4Rotated.setRotation(90);
      document.addPage(a4Rotated);
      document.addPage(new PDPage(new PDRectangle(200, 400)));
      PDPage page3 = new PDPage();
      document.addPage(page3);
      PDPageXYZDestination destination = new PDPageXYZDestination();
      destination.setPage(page3);
      PDActionGoTo action = new PDActionGoTo();
      action.setDestination(destination);
      PDAnnotationLink link = new PDAnnotationLink();
      link.setAction(action);
      link.setRectangle(new PDRectangle(50, 50, 100, 20));
      a4Rotated.getAnnotations().add(link);
    });
    byte[] sourceBefore = Files.readAllBytes(source);
    AssemblyResult result = assembler.assemble(request(tocOnly().withPageNumbers(PageNumbers.BOTTOM_RIGHT_N),
        doc("Leading Doc", twoPage), doc("Linked Doc", source)), workDir);
    Path pdf = result.outputPdf();

    assertThat(Files.readAllBytes(source)).isEqualTo(sourceBefore);
    // Pages: index, 2 leading, source at 4..6: its page-1 -> page-3 link must map to bundle page 6, not 3.
    assertThat(result.items().get(1).startPage()).isEqualTo(4);
    assertThat(Pdfs.internalLinkTargets(pdf, 4)).containsExactly(6);
    Pdfs.read(pdf, document -> {
      assertThat(document.getPage(3).getRotation()).isEqualTo(90);
      assertThat(document.getPage(3).getMediaBox().toString()).isEqualTo(PDRectangle.A4.toString());
      assertThat(document.getPage(4).getMediaBox().toString()).isEqualTo(new PDRectangle(200, 400).toString());
      return null;
    });
    assertThat(Pdfs.pageText(pdf, 4)).containsPattern("(?m)^4$");
    assertThat(Pdfs.pageText(pdf, 5)).containsPattern("(?m)^5$");
    try (Stream<Path> entries = Files.list(workDir)) {
      assertThat(entries).as("only the finished bundle remains in workDir").containsExactly(pdf);
    }
  }

  @Test
  void corruptSourceFailsNamingTheDocumentAndLeavesNoStaleOutput() throws IOException {
    assembler.assemble(request(tocOnly(), doc("Doc 1", twoPage)), workDir);
    assertThat(workDir.resolve("stitched.pdf")).exists();
    Path encrypted = Pdfs.pdf(tmp, "encrypted-", document -> {
      document.addPage(new PDPage());
      document.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
    });

    assertThatThrownBy(() -> assembler.assemble(request(tocOnly(), doc("Doc 1", twoPage),
        doc("Bundle Doc 2", fixture("TestExcelConversion.xlsx"))), workDir))
        .isInstanceOf(IOException.class)
        .hasMessage("Error processing, document title: Bundle Doc 2, file name: TestExcelConversion.xlsx");
    assertThat(workDir.resolve("stitched.pdf")).as("stale output of the earlier run").doesNotExist();
    assertThatThrownBy(() -> assembler.assemble(request(tocOnly(), doc("Sealed Doc", encrypted)), workDir))
        .isInstanceOf(IOException.class).hasMessageContaining("Sealed Doc");
    assertThat(workDir).as("failed assemblies leave nothing behind").isEmptyDirectory();
  }

  @Test
  void backToIndexLinkIsOnPageAndTargetsTheIndexPageCarryingTheEntry() throws IOException {
    Path onePage = textPdf(tmp, "x", 1);
    List<AssemblyNode> items = new ArrayList<>();
    items.add(folder("Folder 1", doc("Folder Doc", onePage)));
    for (int i = 0; i < 60; i++) {
      items.add(doc("Bundle Doc " + i, onePage));
    }
    AssemblyResult result = assembler.assemble(described(
        tocOnly().withSectionCoverSheets(true).withDocumentCoverSheets(true), false, items), workDir);
    Path pdf = result.outputPdf();

    // 7 heading + 3 folder + 1 entry + 1 gap + 60 entries = 72 lines: 2 index pages, folder cover 3, doc cover 4.
    assertThat(result.items().get(0)).isEqualTo(new AssembledItem("Folder Doc", 5, 1));
    PDRectangle rect = Pdfs.linkRects(pdf, 3).get(0);
    assertThat(rect.getLowerLeftX()).isBetween(0f, PDRectangle.LETTER.getWidth());
    assertThat(rect.getUpperRightX()).isLessThanOrEqualTo(PDRectangle.LETTER.getWidth());
    assertThat(rect.getLowerLeftY()).isGreaterThan(PDRectangle.LETTER.getHeight() * 0.9f);
    assertThat(rect.getUpperRightY()).isLessThanOrEqualTo(PDRectangle.LETTER.getHeight());
    assertThat(Pdfs.pageText(pdf, 3)).contains("Folder 1", "Back to index");
    assertThat(Pdfs.internalLinkTargets(pdf, 3)).containsExactly(1);
    assertThat(Pdfs.internalLinkTargets(pdf, 4)).containsExactly(1);
    // The last document's entry sits on index page 2, so its cover sheet links there, not to page 1.
    int lastCover = result.items().get(60).startPage() - 1;
    assertThat(Pdfs.pageText(pdf, 2)).contains("Bundle Doc 59");
    assertThat(Pdfs.pageText(pdf, lastCover)).contains("Bundle Doc 59", "Back to index");
    assertThat(Pdfs.internalLinkTargets(pdf, lastCover)).containsExactly(2);
  }

  @Test
  void tocPageEstimateIsExactAtPageBoundaries() throws IOException {
    Path onePage = textPdf(tmp, "x", 1);
    // With DESCRIPTION the index heading takes 7 of the 38 lines per page.
    for (int[] docsAndPages : new int[][] {{30, 1}, {31, 1}, {32, 2}, {68, 2}, {69, 2}, {70, 3}}) {
      List<AssemblyNode> items = new ArrayList<>();
      for (int i = 0; i < docsAndPages[0]; i++) {
        items.add(doc("Doc " + i, onePage));
      }
      AssemblyResult result = assembler.assemble(described(tocOnly(), false, items),
          tmp.resolve("boundary-" + docsAndPages[0]));
      assertThat(result.totalPages() - docsAndPages[0]).as("%d docs: index pages", docsAndPages[0])
          .isEqualTo(docsAndPages[1]);
      assertIndexPages(result, docsAndPages[1],
          result.items().stream().map(AssembledItem::startPage).toList(), docsAndPages[0] + " docs");
    }

    String wrapping = "Wrapping title word ".repeat(12).trim(); // several index lines
    for (int leading = 26; leading <= 33; leading++) {
      List<AssemblyNode> items = new ArrayList<>();
      for (int i = 0; i < leading; i++) {
        items.add(doc("Doc " + i, onePage));
      }
      items.add(folder("Empty folder skipped entirely"));
      items.add(folder("Folder at boundary " + wrapping, doc(wrapping, onePage)));
      items.add(doc("Trailing " + wrapping, onePage));
      AssemblyResult result = assembler.assemble(
          described(tocOnly().withSectionCoverSheets(true), false, items), tmp.resolve("foldery-" + leading));
      int indexPages = result.totalPages() - leading - 3; // + folder cover, folder doc, trailing doc
      List<Integer> expected = new ArrayList<>(result.items().stream().map(AssembledItem::startPage).toList());
      expected.add(leading, result.items().get(leading).startPage() - 1); // the folder's cover sheet
      assertIndexPages(result, indexPages, expected, leading + " leading docs");
      assertThat(Pdfs.pageText(result.outputPdf(), 1, indexPages)).doesNotContain("Empty folder");
    }
  }

  @Test
  void allInsertionsTogetherKeepLinksBookmarksAndStampsConsistentAndEmptySectionPageIsListedAndWarned()
      throws IOException {
    AssemblyItem placeholder = new AssemblyItem("Applications", Optional.empty(), false, new EmptySectionPage());
    List<AssemblyNode> items = List.of(folder("Section A", doc("A1", twoPage), doc("A2", twoPage)),
        folder("Section B", doc("B1", twoPage)), folder("Section C", placeholder), folder("Empty Folder"),
        doc("Loose Doc", twoPage));
    BundlePresentation everything = new BundlePresentation(true, true, true,
        PageNumbers.BOTTOM_CENTRE_N_OF_M, ConfidentialMarking.APPROVED_HEADER);
    AssemblyResult result = assembler.assemble(described(everything, true, items), workDir);
    Path pdf = result.outputPdf();

    // title page + index + 3 folder covers + 5 doc covers + 9 content pages; the empty folder adds nothing.
    assertThat(result.totalPages()).isEqualTo(1 + 1 + 3 + 5 + 9);
    List<Integer> starts = result.items().stream().map(AssembledItem::startPage).toList();
    assertThat(Pdfs.internalLinkTargets(pdf, 2)).as("contents links vs reported placements").containsExactly(
        3, starts.get(0), starts.get(1), starts.get(2) - 2, starts.get(2), starts.get(3) - 2, starts.get(3),
        starts.get(4));
    assertThat(Pdfs.pageText(pdf, 2)).contains("Applications").doesNotContain("Empty Folder");
    List<String> outline = Pdfs.outline(pdf);
    for (AssembledItem item : result.items()) {
      String indent = "Loose Doc".equals(item.title()) ? "  " : "    ";
      assertThat(outline).as("bookmark for %s points at its cover sheet", item.title())
          .contains(indent + item.title() + " -> " + (item.startPage() - 1));
      for (int page = item.startPage(); page < item.startPage() + item.pageCount(); page++) {
        assertThat(Pdfs.pageText(pdf, page)).as("stamp on page %d", page)
            .contains(page + " of " + result.totalPages());
      }
    }
    assertThat(outline).containsSubsequence("  Section A -> 3", "    A1 -> 4", "    A2 -> 7",
        "  Section B -> 10", "    B1 -> 11", "  Section C -> 14", "    Applications -> 15", "  Loose Doc -> 17");
    for (int page : new int[] {1, 2, 3, 4, 7, 10, 11, 14, 15, 17}) {
      assertThat(Pdfs.pageText(pdf, page)).as("page %d is unstamped", page).doesNotContain(" of 19");
    }
    assertThat(Pdfs.pageText(pdf, 16)).contains("Applications", "There are no documents in this section.");
    assertThat(result.items().get(3)).isEqualTo(new AssembledItem("Applications", 16, 1));
    assertThat(Pdfs.pageText(pdf, 1, 19)).as("no item is confidential").doesNotContain("CONFIDENTIAL");
    assertThat(result.warnings()).singleElement().satisfies(warning -> {
      assertThat(warning.code()).isEqualTo(PdfBundleAssembler.WARNING_EMPTY_SECTION_PAGE);
      assertThat(warning.documentId()).contains("Applications");
    });
  }

  @Test
  void fullyNonWinAnsiTitleGetsFallbackRowAndWarningAndLongBookmarkTitlesAreTrimmedNotMidSurrogate()
      throws IOException {
    Path onePage = textPdf(tmp, "x", 1);
    String astral = "Emoji " + "😀".repeat(300); // 606 code units, 306 code points
    AssemblyResult result = assembler.assemble(request(tocOnly().withDocumentCoverSheets(true),
        doc("Заявление о приёме", LocalDate.of(2024, 5, 1), onePage), doc("Report ąćę", onePage),
        doc("T".repeat(450), onePage), doc(astral, onePage)), workDir);
    Path pdf = result.outputPdf();

    assertThat(Pdfs.pageText(pdf, 1)).contains("Document 1", "1 May 2024", "Report").doesNotContain("Заявление", "ą");
    assertThat(Pdfs.pageText(pdf, 2)).as("cover sheet carries the fallback title").contains("Document 1");
    assertThat(Pdfs.internalLinkTargets(pdf, 1)).containsExactly(3, 5, 7, 9);
    List<String> outline = Pdfs.outline(pdf);
    assertThat(outline).contains("  Заявление о приёме -> 2", "  Report ąćę -> 4", "  " + "T".repeat(399) + "... -> 6");
    String astralBookmark = outline.get(5);
    assertThat(astralBookmark).startsWith("  Emoji 😀").endsWith("... -> 8").hasSizeLessThan(410);
    assertThat(astralBookmark.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF))
        .as("no lone surrogate in %s", astralBookmark.substring(astralBookmark.length() - 8)).isFalse();
    assertThat(result.warnings()).singleElement().satisfies(warning -> {
      assertThat(warning.code()).isEqualTo(PdfBundleAssembler.WARNING_TITLE_NOT_RENDERABLE);
      assertThat(warning.documentId()).contains("Заявление о приёме");
    });
  }

  private static void assertIndexPages(AssemblyResult result, int indexPages, List<Integer> expected, String label) {
    Path pdf = result.outputPdf();
    List<Integer> targets = new ArrayList<>();
    for (int page = 1; page <= indexPages; page++) {
      assertThat(Pdfs.linkRects(pdf, page).stream().map(PDRectangle::getUpperRightY).toList())
          .as("%s: index page %d carries entries laid out top-down without wrapping", label, page)
          .isNotEmpty().isSortedAccordingTo(Comparator.reverseOrder());
      targets.addAll(Pdfs.internalLinkTargets(pdf, page));
    }
    assertThat(targets).as("%s: index links vs placements", label).isEqualTo(expected);
    assertThat(Pdfs.pageText(pdf, indexPages + 1)).as("%s: first content page", label).doesNotContain("Index Page");
  }

  private static float baseline(Path pdf, int page, String needle) {
    float[] found = {Float.NaN};
    Pdfs.read(pdf, document -> Pdfs.io(() -> {
      PDFTextStripper stripper = new PDFTextStripper() {
        @Override
        protected void writeString(String text, List<TextPosition> positions) {
          if (text.contains(needle) && !positions.isEmpty() && Float.isNaN(found[0])) {
            found[0] = positions.get(0).getY();
          }
        }
      };
      stripper.setStartPage(page);
      stripper.setEndPage(page);
      return stripper.getText(document);
    }));
    assertThat(found[0]).as("'%s' on page %d", needle, page).isNotNaN();
    return found[0];
  }

}
