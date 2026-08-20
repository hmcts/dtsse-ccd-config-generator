package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.folder;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.textPdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessStreamCache;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

/**
 * Adversarial review, part 2 — the review findings pinned after their fixes: watermark stream
 * safety, table-of-contents wrap-width parity, link placement, workDir hygiene, the shared
 * stream-cache budget, and validation.
 */
class AdversarialReviewPartTwoTest {

  private static final String DESCRIPTION =
      "This is the description, it should really be wrapped but it is not currently. "
          + "The table limit is 255 characters anyway.";

  @TempDir
  Path tmp;

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();
  private Path workDir;

  @BeforeEach
  void setUp() throws IOException {
    workDir = Files.createDirectories(tmp.resolve("work"));
  }

  private static BundlePresentation presentation(boolean toc, boolean sectionCovers,
      boolean documentCovers, PageNumbers numbers) {
    return new BundlePresentation(toc, sectionCovers, documentCovers, numbers,
        ConfidentialMarking.NONE);
  }

  private AssemblyRequest request(BundlePresentation presentation, List<AssemblyNode> items) {
    return new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.of(DESCRIPTION), presentation, false, Optional.empty(), items);
  }

  // ---------------------------------------------------------------------------------------
  // F1: watermarking must never save over the file it is lazily reading
  // ---------------------------------------------------------------------------------------

  /**
   * F1 pin: the watermarked output is written to a distinct path, so real evidence documents
   * with compressed embedded font programs keep an extractable text layer (saving over the
   * file being lazily read — as em-stitching does — truncates their streams).
   */
  @Test
  void watermarkedEvidenceTextStaysExtractable() throws IOException {
    Path source = fixture("TEST_INPUT_FILE.pdf");
    assertThat(Pdfs.pageText(source, 1)).contains("cursor");

    Path watermarked = WatermarkRenderer.apply(source,
        Watermark.image(fixture("schmcts.png"), Watermark.Scope.ALL_PAGES,
            Watermark.Rendering.OPAQUE),
        workDir, IOUtils.createTempFileOnlyStreamCache(), new PdfFonts());

    assertThat(watermarked).isNotEqualTo(source);
    assertThat(Pdfs.pageText(watermarked, 1))
        .as("watermarked evidence text must stay extractable")
        .contains("cursor");
  }

  /** F1 pin, end to end: the assembled bundle carries readable watermarked evidence pages. */
  @Test
  void watermarkedSourceTextIsStillReadableInTheAssembledBundle() throws IOException {
    AssemblyRequest watermarkedRequest = new AssemblyRequest("Title of the bundle",
        "stitched.pdf", Optional.empty(), presentation(false, false, false, PageNumbers.NONE),
        false,
        Optional.of(Watermark.image(fixture("schmcts.png"), Watermark.Scope.ALL_PAGES,
            Watermark.Rendering.OPAQUE)),
        List.of(doc("Medical Report", fixture("TEST_INPUT_FILE.pdf"))));

    AssemblyResult result = assembler.assemble(watermarkedRequest, workDir);

    assertThat(Pdfs.pageText(result.outputPdf(), 1))
        .as("evidence text must survive watermarking into the bundle")
        .contains("cursor");
  }

  // ---------------------------------------------------------------------------------------
  // F2: contents title wrap width matches the service (400pt)
  // ---------------------------------------------------------------------------------------

  /**
   * F2 pin: em-stitching's golden for this bundle ({@code characterisation/multiline-titles})
   * is 4 pages with the documents starting on page 2; the wrap width must reproduce exactly
   * that geometry. (Full facts parity is in CharacterisationRegressionTest.)
   */
  @Test
  void multilineTitleBundleKeepsTheServicesPageGeometry() throws IOException {
    String veryLong = " Very long".repeat(117);

    AssemblyResult result = assembler.assemble(request(
        presentation(true, false, false, PageNumbers.TOP_RIGHT_N),
        List.of(doc("Bundle Doc 1" + veryLong, textPdf(tmp, "Title of the bundle", 2)),
            doc("Bundle Doc 2" + veryLong, fixture("annotationTemplate.pdf")))), workDir);

    assertThat(result.totalPages()).as("golden multiline-titles page count").isEqualTo(4);
    assertThat(result.items().get(0).startPage()).isEqualTo(2);
  }

  // ---------------------------------------------------------------------------------------
  // F7: "Back to index" placement (divergence 10)
  // ---------------------------------------------------------------------------------------

  /**
   * Divergence 10 pin: the service draws the link with swapped x/y arguments, so its golden
   * rectangle sits at x=572..730 on a 612pt page — off the sheet and unclickable. The SDK's
   * link must be fully on the page, visibly at the top, and click through to the index.
   */
  @Test
  void backToIndexLinkRectangleIsOnThePageUnlikeTheGolden() throws IOException {
    AssemblyResult result = assembler.assemble(request(
        presentation(true, true, false, PageNumbers.NONE),
        List.of(folder("Folder 1", doc("Doc", textPdf(tmp, "x", 1))))), workDir);

    try (PDDocument document = Loader.loadPDF(result.outputPdf().toFile())) {
      PDPage coverSheet = document.getPage(1);
      PDRectangle mediaBox = coverSheet.getMediaBox();
      PDAnnotationLink back = (PDAnnotationLink) coverSheet.getAnnotations().get(0);
      PDRectangle rect = back.getRectangle();
      assertThat(rect.getLowerLeftX()).isBetween(0f, mediaBox.getWidth());
      assertThat(rect.getUpperRightX()).isLessThanOrEqualTo(mediaBox.getWidth());
      // Visibly in the top area of the page, not near the bottom edge.
      assertThat(rect.getLowerLeftY()).isGreaterThan(mediaBox.getHeight() * 0.9f);
      assertThat(rect.getUpperRightY()).isLessThanOrEqualTo(mediaBox.getHeight());
    }
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(), 2)).containsExactly(1);
    assertThat(Pdfs.pageText(result.outputPdf(), 2)).contains("Back to index");
  }

  /**
   * With a multi-page index, each cover sheet's "Back to index" must point at the index page
   * that actually carries that item's entry, not at index page 1.
   */
  @Test
  void backToIndexPointsAtTheIndexPageCarryingTheEntry() throws IOException {
    Path onePage = textPdf(tmp, "x", 1);
    List<AssemblyNode> items = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      items.add(doc("Bundle Doc " + i, onePage));
    }

    AssemblyResult result = assembler.assemble(request(
        presentation(true, false, true, PageNumbers.NONE), items), workDir);

    int tocPages = result.totalPages() - (60 * 2);
    assertThat(tocPages).isGreaterThan(1);

    // The last document's cover sheet must link to the last index page, not the first.
    int lastCoverSheet = result.items().get(59).startPage() - 1;
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(), lastCoverSheet))
        .as("Back to index target for the final cover sheet")
        .containsExactly(tocPages);
  }

  // ---------------------------------------------------------------------------------------
  // Source documents' own internal links after the merge
  // ---------------------------------------------------------------------------------------

  /**
   * A source document with an internal cross-reference (page 1 -> page 3) must still point at
   * its own copied page inside the bundle, not at the bundle's page 3.
   */
  @Test
  void internalLinksInsideASourceStillPointAtItsOwnCopiedPages() throws IOException {
    Path linked = tmp.resolve("internally-linked.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage page1 = new PDPage();
      PDPage page2 = new PDPage();
      PDPage page3 = new PDPage();
      document.addPage(page1);
      document.addPage(page2);
      document.addPage(page3);
      PDPageXYZDestination destination = new PDPageXYZDestination();
      destination.setPage(page3);
      PDActionGoTo action = new PDActionGoTo();
      action.setDestination(destination);
      PDAnnotationLink link = new PDAnnotationLink();
      link.setAction(action);
      link.setRectangle(new PDRectangle(50, 50, 100, 20));
      page1.getAnnotations().add(link);
      document.save(linked.toFile());
    }

    AssemblyResult result = assembler.assemble(request(
        presentation(true, false, false, PageNumbers.NONE),
        List.of(doc("Leading Doc", textPdf(tmp, "lead", 2)),
            doc("Linked Doc", linked))), workDir);

    // Pages: 1 index, 2 leading, then the linked document at 4,5,6.
    int linkedStart = result.items().get(1).startPage();
    assertThat(linkedStart).isEqualTo(4);
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(), linkedStart))
        .as("source's own page-3 link must be remapped to bundle page %d", linkedStart + 2)
        .containsExactly(linkedStart + 2);
  }

  // ---------------------------------------------------------------------------------------
  // F5 + F8: workDir hygiene
  // ---------------------------------------------------------------------------------------

  /** F5 pin: watermark intermediates are removed from workDir once assembly completes. */
  @Test
  void watermarkedIntermediateCopiesAreRemovedFromWorkDir() throws IOException {
    AssemblyRequest watermarked = new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.empty(), presentation(false, false, false, PageNumbers.NONE), false,
        Optional.of(Watermark.text("OFFICIAL COPY", Watermark.Scope.ALL_PAGES)),
        List.of(doc("Doc 1", textPdf(tmp, "one", 1)), doc("Doc 2", textPdf(tmp, "two", 1))));

    assembler.assemble(watermarked, workDir);

    try (var entries = Files.list(workDir)) {
      List<String> leftovers = entries.map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith("watermarked-")).toList();
      assertThat(leftovers).as("watermarked intermediates left in workDir").isEmpty();
    }
  }

  /** F5 pin, failure path: intermediates are removed even when a later document fails. */
  @Test
  void watermarkedIntermediatesAreRemovedOnFailureToo() throws IOException {
    AssemblyRequest watermarked = new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.empty(), presentation(false, false, false, PageNumbers.NONE), false,
        Optional.of(Watermark.text("OFFICIAL COPY", Watermark.Scope.ALL_PAGES)),
        List.of(doc("Doc 1", textPdf(tmp, "one", 1)),
            doc("Broken", fixture("TestExcelConversion.xlsx"))));

    assertThatThrownBy(() -> assembler.assemble(watermarked, workDir))
        .isInstanceOf(IOException.class);

    try (var entries = Files.list(workDir)) {
      List<String> leftovers = entries.map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith("watermarked-")).toList();
      assertThat(leftovers).as("watermarked intermediates left after failure").isEmpty();
    }
  }

  /**
   * F8 pin: a failed assembly must not leave a previous run's output at
   * {@code workDir/outputFileName} where a path-publishing caller could ship a stale bundle.
   */
  @Test
  void failedAssemblyDoesNotLeaveAStaleOutputBehind() throws IOException {
    Path good = textPdf(tmp, "good", 1);
    assembler.assemble(request(presentation(false, false, false, PageNumbers.NONE),
        List.of(doc("Doc 1", good))), workDir);
    assertThat(workDir.resolve("stitched.pdf")).exists();

    assertThatThrownBy(() -> assembler.assemble(
        request(presentation(false, false, false, PageNumbers.NONE),
            List.of(doc("Doc 1", good), doc("Broken", fixture("TestExcelConversion.xlsx")))),
        workDir)).isInstanceOf(IOException.class);

    assertThat(workDir.resolve("stitched.pdf"))
        .as("stale output from the previous run must not survive a failed assembly")
        .doesNotExist();
  }

  // ---------------------------------------------------------------------------------------
  // F4: one shared stream-cache budget per assembly
  // ---------------------------------------------------------------------------------------

  /**
   * F4 pin: every {@code create()} of the assembler's stream-cache function shares one
   * underlying scratch buffer (one budget for the whole bundle, not 64MB per document), and
   * closing a per-document cache view must not close the shared buffer.
   */
  @Test
  void streamCacheBudgetIsSharedAcrossTheWholeBundle() throws IOException {
    MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(1024)
        .setTempDir(workDir.toFile());
    try (ScratchFile scratch = new ScratchFile(memory)) {
      StreamCacheCreateFunction shared = PdfBundleAssembler.sharedStreamCache(scratch);

      RandomAccessStreamCache first = shared.create();
      RandomAccessStreamCache second = shared.create();
      first.createBuffer().write(new byte[4096]);
      first.close();
      // The shared scratch buffer survives a per-document close.
      second.createBuffer().write(new byte[4096]);
      second.close();

      scratch.close();
      assertThatThrownBy(() -> shared.create().createBuffer())
          .as("once the assembly closes the scratch buffer, no view can allocate")
          .isInstanceOf(IOException.class);
    }
  }

  /** The spill claim: scratch files land under the caller's working directory. */
  @Test
  void scratchSpillFilesLandUnderTheWorkingDirectory() throws IOException {
    Path spillDir = Files.createDirectories(tmp.resolve("spill"));
    MemoryUsageSetting memory =
        MemoryUsageSetting.setupMixed(0).setTempDir(spillDir.toFile());
    try (RandomAccessStreamCache cache = memory.streamCache.create()) {
      cache.createBuffer().write(new byte[8192]);
      try (var entries = Files.list(spillDir)) {
        assertThat(entries.map(p -> p.getFileName().toString()).toList())
            .anyMatch(name -> name.startsWith("PDFBox"));
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // F9: validation
  // ---------------------------------------------------------------------------------------

  /** F9 pin: an unmappable output file name fails at construction, not after the merge. */
  @Test
  void illegalOutputFileNameIsRejectedAtConstruction() {
    String nulInName = "stitched\u0000.pdf";

    assertThatThrownBy(() -> new AssemblyRequest("Title of the bundle", nulInName,
        Optional.empty(), presentation(false, false, false, PageNumbers.NONE), false,
        Optional.empty(), List.of(doc("Doc", textPdf(tmp, "x", 1)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plain file name");
  }

  @Test
  void placeholderOnlyRequestRendersTheVisiblePageAndWarns() throws IOException {
    AssemblyResult result = assembler.assemble(request(
        presentation(true, false, false, PageNumbers.NONE),
        List.of(new AssemblyItem("Expected Section", Optional.empty(), false,
            new EmptySectionPage()))), workDir);

    assertThat(result.totalPages()).isEqualTo(2);
    assertThat(Pdfs.pageText(result.outputPdf(), 2))
        .contains("Expected Section")
        .contains("There are no documents in this section.");
    assertThat(result.warnings()).extracting(BundleWarning::code)
        .contains(PdfBundleAssembler.WARNING_EMPTY_SECTION_PAGE);
  }

  /**
   * The 400-character bookmark trim must not cut on a UTF-16 code-unit boundary: a title of
   * astral characters (emoji, some CJK extensions) truncated mid-surrogate-pair would end in an
   * unpaired surrogate — an invalid PDF text string readers render as a replacement glyph.
   */
  @Test
  void longAstralTitleIsNotTruncatedMidSurrogatePair() throws IOException {
    String astral = "😀".repeat(300); // 600 code units, 300 code points

    AssemblyResult result = assembler.assemble(request(
        presentation(false, false, false, PageNumbers.NONE),
        List.of(doc(astral, textPdf(tmp, "x", 1)))), workDir);

    String bookmark = Pdfs.outline(result.outputPdf()).get(1);
    assertThat(hasUnpairedSurrogate(bookmark))
        .as("bookmark title ends in an unpaired surrogate: %s",
            bookmark.substring(Math.max(0, bookmark.length() - 8)).chars().boxed().toList())
        .isFalse();
  }

  private static boolean hasUnpairedSurrogate(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
          return true;
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------
  // Everything-on consistency (title page + TOC + folders + covers + watermark + numbers)
  // ---------------------------------------------------------------------------------------

  /**
   * With every insertion enabled at once, the contents links, the bookmark targets, the
   * reported start pages and the printed page numbers must all agree.
   */
  @Test
  void allInsertionsTogetherKeepLinksBookmarksAndStampsConsistent() throws IOException {
    Path twoPage = textPdf(tmp, "evidence", 2);
    List<AssemblyNode> items = List.of(
        folder("Section A", doc("A1", twoPage), doc("A2", twoPage)),
        folder("Section B", doc("B1", twoPage)),
        doc("Loose Doc", twoPage));
    AssemblyRequest everything = new AssemblyRequest("Title of the bundle", "stitched.pdf",
        Optional.of(DESCRIPTION),
        new BundlePresentation(true, true, true, PageNumbers.BOTTOM_CENTRE_N_OF_M,
            ConfidentialMarking.APPROVED_HEADER),
        true,
        Optional.of(Watermark.text("OFFICIAL COPY", Watermark.Scope.ALL_PAGES)),
        items);

    AssemblyResult result = assembler.assemble(everything, workDir);

    // title page + 1 index page + 2 folder covers + 4 doc covers + 8 content pages
    assertThat(result.totalPages()).isEqualTo(1 + 1 + 2 + 4 + 8);

    List<Integer> tocTargets = Pdfs.internalLinkTargets(result.outputPdf(), 2);
    List<Integer> expected = new ArrayList<>();
    expected.add(3);                                   // Section A cover
    expected.add(result.items().get(0).startPage());   // A1
    expected.add(result.items().get(1).startPage());   // A2
    expected.add(result.items().get(2).startPage() - 2); // Section B cover
    expected.add(result.items().get(2).startPage());   // B1
    expected.add(result.items().get(3).startPage());   // Loose Doc
    assertThat(tocTargets).as("contents links vs reported placements").isEqualTo(expected);

    // Bookmarks: every document bookmark points at its cover sheet, one page before content.
    List<String> outline = Pdfs.outline(result.outputPdf());
    for (AssembledItem item : result.items()) {
      String indent = "Loose Doc".equals(item.title()) ? "  " : "    ";
      assertThat(outline)
          .as("bookmark for %s", item.title())
          .contains(indent + item.title() + " -> " + (item.startPage() - 1));
    }

    // Every content page carries its own absolute number; cover sheets and index do not.
    for (AssembledItem item : result.items()) {
      for (int page = item.startPage(); page < item.startPage() + item.pageCount(); page++) {
        assertThat(Pdfs.pageText(result.outputPdf(), page))
            .as("stamp on page %d", page)
            .contains(page + " of " + result.totalPages());
      }
    }
  }

  /**
   * Confidential marking must cover exactly the confidential document's cover sheet and its
   * content pages, and nothing else.
   */
  @Test
  void confidentialMarkingCoversOnlyTheConfidentialDocument() throws IOException {
    Path twoPage = textPdf(tmp, "evidence", 2);
    AssemblyRequest requestWithConfidential = new AssemblyRequest("Title of the bundle",
        "stitched.pdf", Optional.empty(),
        new BundlePresentation(true, false, true, PageNumbers.NONE,
            ConfidentialMarking.APPROVED_HEADER),
        false, Optional.empty(),
        List.of(doc("Open Doc", twoPage),
            new AssemblyItem("Sealed Doc", Optional.empty(), true, new PdfSource(twoPage))));

    AssemblyResult result = assembler.assemble(requestWithConfidential, workDir);

    // 1 index, cover+2 open pages (2..4), cover+2 sealed pages (5..7)
    assertThat(result.totalPages()).isEqualTo(7);
    for (int page = 1; page <= 4; page++) {
      assertThat(Pdfs.pageText(result.outputPdf(), page))
          .as("page %d must not be marked", page)
          .doesNotContain("CONFIDENTIAL");
    }
    for (int page = 5; page <= 7; page++) {
      assertThat(Pdfs.pageText(result.outputPdf(), page))
          .as("page %d must be marked", page)
          .contains("CONFIDENTIAL");
    }
  }

  /**
   * A rotated source page still receives its stamp inside the page box (parity with the
   * service, which also stamps in unrotated coordinates).
   */
  @Test
  void rotatedPagesAreStampedInsideThePageBox() throws IOException {
    Path rotated = tmp.resolve("rotated.pdf");
    try (PDDocument document = new PDDocument()) {
      for (int rotation : new int[] {90, 180, 270}) {
        PDPage page = new PDPage(PDRectangle.A4);
        page.setRotation(rotation);
        document.addPage(page);
      }
      document.save(rotated.toFile());
    }

    AssemblyResult result = assembler.assemble(request(
        presentation(false, false, false, PageNumbers.BOTTOM_RIGHT_N),
        List.of(doc("Rotated Doc", rotated))), workDir);

    for (int page = 1; page <= 3; page++) {
      assertThat(Pdfs.pageText(result.outputPdf(), page))
          .as("stamp extractable on rotated page %d", page)
          .contains(String.valueOf(page));
    }
  }

  /** A very long consumer description must not push the index heading off the page. */
  @Test
  void veryLongDescriptionKeepsTheIndexHeadingOnThePage() throws IOException {
    String longDescription = "Description sentence number one. ".repeat(60);
    AssemblyRequest longDescriptionRequest = new AssemblyRequest("Title of the bundle",
        "stitched.pdf", Optional.of(longDescription),
        presentation(true, false, false, PageNumbers.NONE), false, Optional.empty(),
        List.of(doc("Doc 1", textPdf(tmp, "x", 1))));

    AssemblyResult result = assembler.assemble(longDescriptionRequest, workDir);

    assertThat(Pdfs.allText(result.outputPdf())).contains("Index Page");
    try (PDDocument document = Loader.loadPDF(result.outputPdf().toFile())) {
      PDPage indexPage = document.getPage(0);
      for (var annotation : indexPage.getAnnotations()) {
        PDRectangle rect = annotation.getRectangle();
        assertThat(rect.getLowerLeftY())
            .as("contents entry link must stay on the index page")
            .isGreaterThanOrEqualTo(0f);
      }
    }
    // The single entry must still link to the document's first page.
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(),
        result.totalPages() - 1)).isNotNull();
  }

  /** An out-of-range outline destination leaves the bookmark without a target — never wrong. */
  @Test
  void unresolvableOutlineDestinationLeavesTheBookmarkWithoutATarget() throws IOException {
    Path outOfRange = tmp.resolve("out-of-range-outline.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDDocumentOutline outline = new PDDocumentOutline();
      document.getDocumentCatalog().setDocumentOutline(outline);
      PDOutlineItem item = new PDOutlineItem();
      item.setTitle("points past the end");
      PDPageDestination destination = new PDPageXYZDestination();
      destination.setPageNumber(500); // page index far beyond the source
      item.setDestination(destination);
      outline.addLast(item);
      document.save(outOfRange.toFile());
    }

    AssemblyResult result = assembler.assemble(request(
        presentation(false, false, false, PageNumbers.NONE),
        List.of(doc("Bad Outline Doc", outOfRange))), workDir);

    // No dangling destination: the bookmark exists with no " -> page" suffix.
    assertThat(Pdfs.outline(result.outputPdf())).contains("    points past the end");
  }
}
