package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.folder;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.textPdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;
import uk.gov.hmcts.ccd.sdk.bundling.testsupport.PdfSemantics;

/**
 * Adversarial review of the PDF assembly layer: estimation arithmetic, degenerate and malicious
 * inputs, evidence safety and concurrency. Golden rendering parity lives in
 * {@code testsupport.CharacterisationRegressionTest}; the image-watermark golden is compared
 * here because applying a watermark without assembling a bundle needs the package-private
 * {@link WatermarkRenderer}.
 */
class AdversarialReviewTest {

  private static final String DESCRIPTION =
      "This is the description, it should really be wrapped but it is not currently. "
          + "The table limit is 255 characters anyway.";

  @TempDir
  Path tmp;

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();
  private Path workDir;

  @BeforeEach
  void setUp() {
    workDir = tmp.resolve("work");
  }

  private static BundlePresentation presentation(boolean toc, boolean sectionCovers,
      boolean documentCovers, PageNumbers numbers) {
    return new BundlePresentation(toc, sectionCovers, documentCovers, numbers,
        ConfidentialMarking.NONE);
  }

  private AssemblyRequest request(String title, String description,
      BundlePresentation presentation, List<AssemblyNode> items) {
    return new AssemblyRequest(title, "stitched.pdf",
        Optional.ofNullable(description), presentation, false, Optional.empty(), items);
  }

  // ---------------------------------------------------------------------------------------
  // Golden parity: image watermark (divergence 11 — readable text exceeds the golden)
  // ---------------------------------------------------------------------------------------

  /**
   * The image-watermark golden's {@code pages[].images} must match exactly; its
   * {@code pages[].text} is stream-corruption garbage (em-stitching saves over the file it is
   * lazily reading), so the SDK's text is instead pinned to the ORIGINAL document's readable
   * text — deliberately exceeding the golden, never merely differing from it.
   */
  @Test
  void imageWatermarkGoldenImagesMatchAndTextExceedsTheCorruptGolden() throws IOException {
    Path source = fixture("TEST_INPUT_FILE.pdf");
    Files.createDirectories(workDir);
    Path watermarked = WatermarkRenderer.apply(source,
        Watermark.image(fixture("schmcts.png"), Watermark.Scope.ALL_PAGES,
            Watermark.Rendering.OPAQUE),
        workDir, IOUtils.createTempFileOnlyStreamCache(), new PdfFonts());

    JsonNode actual = PdfSemantics.extract(watermarked);
    JsonNode expected = PdfSemantics.readFacts(Path.of(
        getClass().getResource("/characterisation/image-watermark/facts.json").getPath()));
    JsonNode original = PdfSemantics.extract(source);

    assertThat(actual.get("pageCount").intValue())
        .isEqualTo(expected.get("pageCount").intValue());
    for (int page = 0; page < expected.get("pageCount").intValue(); page++) {
      assertThat(actual.get("pages").get(page).get("images"))
          .as("watermark image facts on page %d", page + 1)
          .isEqualTo(expected.get("pages").get(page).get("images"));
      assertThat(actual.get("pages").get(page).get("text").asText())
          .as("watermarked text must be the original's readable text, page %d", page + 1)
          .isEqualTo(original.get("pages").get(page).get("text").asText());
    }
  }

  // ---------------------------------------------------------------------------------------
  // Table-of-contents estimation arithmetic
  // ---------------------------------------------------------------------------------------

  /**
   * The estimate must equal the renderer's own accounting for boundary bundles: exactly-full
   * pages, folders whose blank lines straddle the boundary, empty folders interleaved, and
   * titles that wrap. For every configuration the TOC links must land exactly on each item's
   * reported start page — one page off means the feedback loop broke.
   */
  @Test
  void tocEstimateIsExactAtPageBoundaries() throws IOException {
    Path onePage = textPdf(tmp, "x", 1);
    // 7 heading lines with this description (2 wrapped lines). One-line titles.
    for (int docs : new int[] {30, 31, 32, 68, 69, 70}) {
      List<AssemblyNode> items = new ArrayList<>();
      for (int i = 0; i < docs; i++) {
        items.add(doc("Doc " + i, onePage));
      }
      AssemblyRequest request = request("Title of the bundle", DESCRIPTION,
          presentation(true, false, false, PageNumbers.NONE), items);
      int estimated = TocRenderer.estimatePages(request);

      AssemblyResult result = assembler.assemble(request, tmp.resolve("boundary-" + docs));
      int tocPages = result.totalPages() - docs;
      assertThat(tocPages).as("%d docs: rendered TOC pages", docs).isEqualTo(estimated);

      // Every TOC link must land exactly on the item's reported start page.
      List<Integer> allTocTargets = new ArrayList<>();
      for (int page = 1; page <= tocPages; page++) {
        allTocTargets.addAll(Pdfs.internalLinkTargets(result.outputPdf(), page));
      }
      List<Integer> startPages =
          result.items().stream().map(AssembledItem::startPage).toList();
      assertThat(allTocTargets).as("%d docs: TOC links vs start pages", docs)
          .isEqualTo(startPages);
    }
  }

  @Test
  void tocEstimateIsExactWithFoldersAndWrappingTitlesAtBoundaries() throws IOException {
    Path onePage = textPdf(tmp, "x", 1);
    String wrapping = "Wrapping title word ".repeat(12).trim(); // multiple TOC lines
    for (int leading : new int[] {26, 27, 28, 29, 30, 31, 32, 33}) {
      List<AssemblyNode> items = new ArrayList<>();
      for (int i = 0; i < leading; i++) {
        items.add(doc("Doc " + i, onePage));
      }
      items.add(folder("Empty folder skipped entirely")); // must not count
      items.add(folder("Folder at boundary " + wrapping, doc(wrapping, onePage)));
      items.add(doc("Trailing " + wrapping, onePage));
      AssemblyRequest request = request("Title of the bundle", DESCRIPTION,
          presentation(true, true, false, PageNumbers.NONE), items);
      int estimated = TocRenderer.estimatePages(request);

      AssemblyResult result =
          assembler.assemble(request, tmp.resolve("foldery-" + leading));
      int contentPages = leading + 1 /* folder cover */ + 1 + 1;
      int tocPages = result.totalPages() - contentPages;
      assertThat(tocPages).as("%d leading docs: rendered TOC pages", leading)
          .isEqualTo(estimated);

      List<Integer> allTocTargets = new ArrayList<>();
      for (int page = 1; page <= tocPages; page++) {
        allTocTargets.addAll(Pdfs.internalLinkTargets(result.outputPdf(), page));
      }
      // Folder link points at its cover sheet; item links at their start pages.
      List<Integer> expected = new ArrayList<>();
      for (AssembledItem item : result.items()) {
        expected.add(item.startPage());
      }
      int folderCover = result.items().get(leading).startPage() - 1;
      expected.add(leading, folderCover);
      assertThat(allTocTargets).as("%d leading docs: TOC links", leading).isEqualTo(expected);
    }
  }

  // ---------------------------------------------------------------------------------------
  // Degenerate and malicious input
  // ---------------------------------------------------------------------------------------

  @Test
  void encryptedSourceFailsWithIoExceptionNamingTheDocument() throws IOException {
    Path encrypted = tmp.resolve("encrypted.pdf");
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      AccessPermission permissions = new AccessPermission();
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("owner-pass", "user-pass", permissions);
      policy.setEncryptionKeyLength(128);
      document.protect(policy);
      document.save(encrypted.toFile());
    }

    assertThatThrownBy(() -> assembler.assemble(request("Title of the bundle", null,
        presentation(true, false, false, PageNumbers.NONE),
        List.of(doc("Sealed Doc", encrypted))), workDir))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Sealed Doc");
  }

  @Test
  void zeroPageSourceFailsWithIoExceptionNamingTheDocument() throws IOException {
    Path zeroPages = tmp.resolve("zero.pdf");
    try (PDDocument document = new PDDocument()) {
      document.save(zeroPages.toFile());
    }

    assertThatThrownBy(() -> assembler.assemble(request("Title of the bundle", null,
        presentation(true, false, false, PageNumbers.NONE),
        List.of(doc("Empty Doc", zeroPages))), workDir))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Empty Doc");
  }

  @Test
  void circularSiblingOutlineIsTruncatedNotLoopedForever() throws IOException {
    Path cyclic = tmp.resolve("cyclic.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDDocumentOutline outline = new PDDocumentOutline();
      document.getDocumentCatalog().setDocumentOutline(outline);
      PDOutlineItem item = new PDOutlineItem();
      item.setTitle("Self-referencing");
      item.setDestination(page);
      outline.addLast(item);
      // Malicious: the item is its own next sibling.
      item.getCOSObject().setItem(COSName.NEXT, item.getCOSObject());
      document.save(cyclic.toFile());
    }

    AssemblyResult result = assembler.assemble(request("Title of the bundle", null,
        presentation(true, false, false, PageNumbers.NONE),
        List.of(doc("Cyclic Doc", cyclic))), workDir);

    assertThat(Pdfs.outline(result.outputPdf()))
        .contains("    Self-referencing -> 2")
        .hasSize(4); // root, Index, Cyclic Doc, one copy of the loop entry
    assertThat(result.warnings()).extracting(BundleWarning::code)
        .contains(PdfBundleAssembler.WARNING_OUTLINE_TRUNCATED);
  }

  @Test
  void circularChildOutlineIsTruncatedNotLoopedForever() throws IOException {
    Path cyclic = tmp.resolve("cyclic-child.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDDocumentOutline outline = new PDDocumentOutline();
      document.getDocumentCatalog().setDocumentOutline(outline);
      PDOutlineItem item = new PDOutlineItem();
      item.setTitle("Own child");
      item.setDestination(page);
      outline.addLast(item);
      // Malicious: the item is its own first child.
      item.getCOSObject().setItem(COSName.FIRST, item.getCOSObject());
      document.save(cyclic.toFile());
    }

    AssemblyResult result = assembler.assemble(request("Title of the bundle", null,
        presentation(true, false, false, PageNumbers.NONE),
        List.of(doc("Cyclic Doc", cyclic))), workDir);

    assertThat(Pdfs.outline(result.outputPdf())).contains("    Own child -> 2");
    assertThat(result.warnings()).extracting(BundleWarning::code)
        .contains(PdfBundleAssembler.WARNING_OUTLINE_TRUNCATED);
  }

  /**
   * A source outline nested deeper than the JVM stack must not kill the assembly with a raw
   * {@link StackOverflowError} (an Error would escape the assembler's typed-error wrapping):
   * the copy is capped at {@link OutlineBuilder#MAX_COPY_DEPTH} levels, deeper nesting is
   * dropped, and the truncation is reported so the assembler can warn naming the document.
   */
  @Test
  void deeplyNestedMaliciousOutlineIsCappedNotStackOverflowed() throws IOException {
    try (PDDocument merged = new PDDocument()) {
      merged.addPage(new PDPage());
      OutlineBuilder builder = new OutlineBuilder(merged, "bundle");
      PDDocumentOutline source = new PDDocumentOutline();
      COSDictionary parent = source.getCOSObject();
      for (int i = 0; i < 100_000; i++) {
        COSDictionary child = new COSDictionary();
        child.setString(COSName.TITLE, "L" + i);
        parent.setItem(COSName.FIRST, child);
        parent.setItem(COSName.LAST, child);
        parent = child;
      }

      boolean truncated = builder.copySourceOutline(builder.root(), source,
          merged.getDocumentCatalog(), 0);

      assertThat(truncated).as("the capped copy must report truncation").isTrue();
      int depth = 0;
      PDOutlineItem copied = builder.root().getFirstChild();
      while (copied != null) {
        depth++;
        copied = copied.getFirstChild();
      }
      assertThat(depth).isEqualTo(OutlineBuilder.MAX_COPY_DEPTH);
    }
  }

  // ---------------------------------------------------------------------------------------
  // Evidence safety
  // ---------------------------------------------------------------------------------------

  /**
   * Stamps are positioned against the CropBox: on a page whose visible area is smaller than its
   * media box (routine in scanned evidence), the CONFIDENTIAL header must land inside the
   * visible area. The current service positions against the MediaBox and draws the legally
   * required marking invisibly above the crop.
   */
  @Test
  void confidentialHeaderStaysInsideTheVisibleCropBox() throws IOException {
    Path cropped = tmp.resolve("cropped.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(new PDRectangle(612, 792));
      page.setCropBox(new PDRectangle(100, 100, 400, 500)); // visible y: 100..600
      document.addPage(page);
      document.save(cropped.toFile());
    }
    var presentation = new BundlePresentation(false, false, false, PageNumbers.NONE,
        ConfidentialMarking.APPROVED_HEADER);

    AssemblyResult result = assembler.assemble(new AssemblyRequest("Title of the bundle",
        "stitched.pdf", Optional.empty(), presentation, false, Optional.empty(),
        List.of(new AssemblyItem("Secret Doc", Optional.empty(), true,
            new PdfSource(cropped)))), workDir);

    float[] markY = {Float.NaN};
    try (PDDocument merged = org.apache.pdfbox.Loader.loadPDF(result.outputPdf().toFile())) {
      PDFTextStripper stripper = new PDFTextStripper() {
        @Override
        protected void writeString(String text, List<TextPosition> positions) {
          if (text.contains("CONFIDENTIAL") && !positions.isEmpty()) {
            markY[0] = positions.get(0).getY(); // measured from the top of the crop box
          }
        }
      };
      stripper.getText(merged);
      PDRectangle cropBox = merged.getPage(0).getCropBox();
      assertThat(markY[0]).isNotNaN();
      // The glyph baseline must fall inside the visible (cropped) area.
      float absoluteY = cropBox.getUpperRightY() - markY[0];
      assertThat(absoluteY)
          .as("CONFIDENTIAL baseline must be inside the crop box (y %s..%s)",
              cropBox.getLowerLeftY(), cropBox.getUpperRightY())
          .isBetween(cropBox.getLowerLeftY(), cropBox.getUpperRightY());
    }
  }

  /**
   * A title containing no WinAnsi-encodable characters (e.g. fully Cyrillic) must not leave an
   * unlabelled row in a court index: the deterministic fallback row text is drawn, a warning
   * names the document, and the bookmark keeps the original title.
   */
  @Test
  void fullyNonWinAnsiTitleGetsFallbackRowTextAndWarning() throws IOException {
    Path onePage = textPdf(tmp, "x", 1);
    AssemblyResult result = assembler.assemble(request("Title of the bundle", null,
        presentation(true, false, false, PageNumbers.NONE),
        List.of(doc("Заявление о приёме", LocalDate.of(2024, 5, 1), onePage),
            doc("Visible Doc", LocalDate.of(2024, 5, 2), onePage))), workDir);

    String toc = Pdfs.pageText(result.outputPdf(), 1);
    // The Cyrillic title cannot be drawn; the deterministic fallback labels the row instead.
    assertThat(toc).doesNotContain("Заявление");
    assertThat(toc).contains("Document 1");
    assertThat(toc).contains("1 May 2024");
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(), 1)).containsExactly(2, 3);
    // The bookmark keeps the original title; the warning names the document.
    assertThat(Pdfs.outline(result.outputPdf())).contains("  Заявление о приёме -> 2");
    assertThat(result.warnings()).anySatisfy(warning -> {
      assertThat(warning.code()).isEqualTo(PdfBundleAssembler.WARNING_TITLE_NOT_RENDERABLE);
      assertThat(warning.documentId()).contains("Заявление о приёме");
    });
  }

  // ---------------------------------------------------------------------------------------
  // Concurrency
  // ---------------------------------------------------------------------------------------

  /**
   * Fonts are created per assembly ({@code PDType1Font} caches glyph encodings in an
   * unsynchronised map), so concurrent assemblies with encoding-heavy titles must not
   * interfere.
   */
  @Test
  void concurrentAssembliesDoNotInterfere() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int t = 0; t < 6; t++) {
        final int thread = t;
        futures.add(pool.submit((Callable<Integer>) () -> {
          Path dir = Files.createDirectories(tmp.resolve("concurrent-" + thread));
          Path source = textPdf(Files.createDirectories(dir.resolve("in")),
              "content " + thread, 1);
          int pages = 0;
          for (int i = 0; i < 5; i++) {
            String title = "Ĉoncurrent£ document №" + thread + "-" + i
                + " ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖ×ØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõö÷øùúûüýþÿ";
            AssemblyResult result = assembler.assemble(request("Bundle " + thread, null,
                presentation(true, false, false, PageNumbers.BOTTOM_CENTRE_N_OF_M),
                List.of(doc(title, source))), dir.resolve("work-" + i));
            pages += result.totalPages();
          }
          return pages;
        }));
      }
      for (Future<Integer> future : futures) {
        assertThat(future.get()).isEqualTo(10);
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
