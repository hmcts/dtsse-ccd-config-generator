package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.request;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.sha256;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;
import uk.gov.hmcts.ccd.sdk.bundling.api.WatermarkPreset;
import uk.gov.hmcts.ccd.sdk.bundling.convert.FileBackedSource;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;

class BundleRenderingTest {
  @TempDir
  Path work;

  private final byte[] onePagePdf = fixture("one-page.pdf");
  private final byte[] multiPagePdf = fixture("Potential_Energy_PDF.pdf");
  private final byte[] jpeg = fixture("flying-pig.jpg");
  private final byte[] png = fixture("schmcts.png");
  private final BundleExecutionContext context = BundleExecutionContext.builder()
      .caseReference("1234567890123456").initiator("rendering-test").build();
  private RenderTestSupport.InMemoryResolver resolver;
  private BundleRenderer renderer;

  @BeforeEach
  void setUp() {
    resolver = new RenderTestSupport.InMemoryResolver()
        .source("ref-cover", RenderTestSupport.Source.of(onePagePdf, "application/pdf", "cover.pdf"))
        // Parameterised declared type: the pipeline must strip parameters before lookup.
        .source("ref-report", RenderTestSupport.Source.of(multiPagePdf, "application/pdf;charset=UTF-8", "report.pdf"))
        .source("ref-photo", RenderTestSupport.Source.of(jpeg, "image/jpeg", "photo.jpg"))
        .source("ref-logo", RenderTestSupport.Source.of(png, "image/png", "logo.png"));
    renderer = BundleRenderer.builder().resolver(resolver).tempDirectory(work).build();
  }

  @Test
  void rendersAMultiSectionBundleAndReportsIt() throws Exception {
    UUID externalId = UUID.randomUUID();
    BundleRequest request = BundleRequest.builder()
        .externalId(externalId).title("Final hearing bundle").fileName("final-hearing-bundle.pdf")
        .root(BundleSection.builder("Case file")
            .document(doc("cover", "Cover letter", "ref-cover"))
            .section(BundleSection.builder("Evidence")
                .document(doc("report", "Expert report", "ref-report"))
                .document(doc("photo", "Site photograph", "ref-photo"))
                .document(doc("logo", "Court logo", "ref-logo")).build())
            .section(BundleSection.builder("Recordings")
                .document(RenderTestSupport.mediaDoc("hearing-audio", "Hearing recording, day 2", "audio/mpeg"))
                .build())
            .section(BundleSection.builder("Correspondence")
                .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER).build())
            .build())
        .build();
    int reportPages;
    try (PDDocument report = Loader.loadPDF(multiPagePdf)) {
      reportPages = report.getNumberOfPages();
    }

    BundleResult result = renderer.render(request, context);
    try (result; PDDocument document = RenderTestSupport.loadPdf(result)) {
      // The two images have no text layer and the empty section's placeholder page is included:
      // every warning is by design.
      assertThat(result.outcome()).isEqualTo(BundleOutcome.COMPLETED_WITH_WARNINGS);
      assertThat(result.warnings()).extracting(BundleWarning::code, w -> w.documentId().orElse(null))
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(DefaultBundleRenderer.WARNING_NO_EXTRACTABLE_TEXT, "photo"),
              org.assertj.core.groups.Tuple.tuple(DefaultBundleRenderer.WARNING_NO_EXTRACTABLE_TEXT, "logo"),
              org.assertj.core.groups.Tuple.tuple(PdfBundleAssembler.WARNING_EMPTY_SECTION_PAGE, "Correspondence"));
      byte[] pdf;
      try (InputStream in = result.artifact().open()) {
        pdf = in.readAllBytes();
      }
      assertThat(result.artifact().fileName()).isEqualTo("final-hearing-bundle.pdf");
      assertThat(result.artifact().mediaType()).isEqualTo("application/pdf");
      assertThat(result.artifact().size()).isEqualTo(pdf.length);
      assertThat(result.artifact().sha256()).isEqualTo(sha256(pdf));

      // One batched fetch of the unique references.
      assertThat(resolver.batches).hasSize(1);
      assertThat(resolver.batches.get(0)).extracting(DocumentReference::id)
          .containsExactly("ref-cover", "ref-report", "ref-photo", "ref-logo");

      // The generation report, in bundle order, with effective types and source checksums.
      assertThat(result.documents()).extracting(DocumentResult::documentId)
          .containsExactly("cover", "report", "photo", "logo", "hearing-audio");
      assertThat(result.documents()).extracting(DocumentResult::mediaType)
          .containsExactly("application/pdf", "application/pdf", "image/jpeg", "image/png", "audio/mpeg");
      assertThat(result.documents()).extracting(DocumentResult::sha256)
          .startsWith(sha256(onePagePdf), sha256(multiPagePdf), sha256(jpeg), sha256(png));
      assertThat(result.documents()).extracting(DocumentResult::pageCount)
          .containsExactly(1, reportPages, 1, 1, 1);
      // p1 title, p2 contents, p3 cover, p4 Evidence cover sheet, then its documents, then the
      // Recordings cover sheet, the media link page, and the empty-section placeholder.
      assertThat(result.documents()).extracting(DocumentResult::startPage)
          .containsExactly(3, 5, 5 + reportPages, 6 + reportPages, 8 + reportPages);
      assertThat(result.pageCount()).isEqualTo(9 + reportPages);

      // Output PDF semantics.
      assertThat(document.getNumberOfPages()).isEqualTo(result.pageCount());
      assertThat(new PDFTextStripper().getText(document))
          .contains("Final hearing bundle", "Index Page", "Cover letter", "Expert report",
              "Hearing recording, day 2", "Media type: audio/mpeg",
              "https://media.example.net/recordings/hearing-audio", "Correspondence",
              "There are no documents in this section.");
      assertThat(RenderTestSupport.outlineTitles(document))
          .contains("Evidence", "Recordings", "Correspondence", "Cover letter", "Expert report",
              "Site photograph", "Court logo", "Hearing recording, day 2");

      // The job directory lives under the temp dir until the result is closed.
      try (Stream<Path> entries = Files.list(work)) {
        assertThat(entries).singleElement().satisfies(dir ->
            assertThat(dir.getFileName().toString()).startsWith("ccd-bundling-" + externalId));
      }
    }
    RenderTestSupport.assertNothingLeftBehind(work);
  }

  @Test
  void deduplicatesIdenticalReferencesAcrossTheTree() throws Exception {
    BundleRequest duplicated = BundleRequest.builder()
        .externalId(UUID.randomUUID()).title("Duplicated bundle").fileName("duplicated.pdf")
        .root(BundleSection.builder("Case file")
            .document(doc("first", "First placement", "ref-cover"))
            .section(BundleSection.builder("Again").document(doc("second", "Second placement", "ref-cover")).build())
            .build())
        .build();

    BundleResult result = renderer.render(duplicated, context);
    try (result; PDDocument document = RenderTestSupport.loadPdf(result)) {
      assertThat(resolver.batches).hasSize(1);
      assertThat(resolver.batches.get(0)).extracting(DocumentReference::id).containsExactly("ref-cover");
      assertThat(result.documents()).extracting(DocumentResult::documentId).containsExactly("first", "second");
      assertThat(result.documents()).extracting(DocumentResult::sha256)
          .containsExactly(sha256(onePagePdf), sha256(onePagePdf));
      assertThat(result.documents().get(0).startPage()).isLessThan(result.documents().get(1).startPage());
      assertThat(RenderTestSupport.outlineTitles(document)).contains("First placement", "Second placement");
    }
  }

  @Test
  void cleanBundleHasNoWarnings() {
    try (BundleResult result = renderer.render(request(doc("cover", "Cover letter", "ref-cover")), context)) {
      assertThat(result.outcome()).isEqualTo(BundleOutcome.COMPLETED);
      assertThat(result.warnings()).isEmpty();
      assertThat(result.pageCount()).isEqualTo(3);
    }
    RenderTestSupport.assertNothingLeftBehind(work);
  }

  @Test
  void emptyRootWithPlaceholderPolicyRendersThePlaceholderPage() throws Exception {
    BundleRequest empty = BundleRequest.builder().externalId(UUID.randomUUID()).title("Empty bundle")
        .fileName("empty.pdf")
        .root(BundleSection.builder("Case file")
            .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER).build())
        .build();
    try (BundleResult result = renderer.render(empty, context);
        PDDocument pdf = RenderTestSupport.loadPdf(result)) {
      assertThat(result.warnings()).extracting(BundleWarning::code)
          .containsExactly(PdfBundleAssembler.WARNING_EMPTY_SECTION_PAGE);
      assertThat(result.documents()).isEmpty();
      assertThat(new PDFTextStripper().getText(pdf)).contains("There are no documents in this section.");
    }
  }

  @Test
  void scannedEvidenceWithoutTextIsBundledWithTheNoTextWarning() throws Exception {
    try (BundleResult result = renderer.render(
        request(doc("d1", "Cover letter", "ref-cover"), doc("d2", "Site photograph", "ref-photo")), context)) {
      assertThat(result.outcome()).isEqualTo(BundleOutcome.COMPLETED_WITH_WARNINGS);
      assertThat(result.warnings()).singleElement().satisfies(warning -> {
        assertThat(warning.code()).isEqualTo(DefaultBundleRenderer.WARNING_NO_EXTRACTABLE_TEXT);
        assertThat(warning.documentId()).contains("d2");
        assertThat(warning.message()).contains("'d2'").contains("OCR");
      });
      assertThat(result.timings()).containsKey(BundleStage.INSPECT);
    }

    // Text that only starts deep into a document still counts: every page is inspected.
    java.io.ByteArrayOutputStream lateText = new java.io.ByteArrayOutputStream();
    try (PDDocument document = new PDDocument()) {
      for (int i = 0; i < 25; i++) {
        document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      }
      org.apache.pdfbox.pdmodel.PDPage last = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(last);
      try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, last)) {
        content.beginText();
        content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 700);
        content.showText("Appendix");
        content.endText();
      }
      document.save(lateText);
    }
    resolver.source("ref-late", RenderTestSupport.Source.of(lateText.toByteArray(), "application/pdf", "late.pdf"));
    try (BundleResult result = renderer.render(request(doc("d3", "Exhibits", "ref-late")), context)) {
      assertThat(result.warnings()).isEmpty();
    }
  }

  @Test
  void registeredWatermarkPresetStampsEverySourcePageAndNoGeneratedPage() throws Exception {
    Path logo = Files.write(work.resolveSibling(work.getFileName() + "-logo.png"), png);
    BundleRenderer watermarking = BundleRenderer.builder().resolver(resolver).tempDirectory(work)
        .watermarkImage("hmcts", logo).build();
    BundlePresentation presentation =
        new BundlePresentation(true, true, false, PageNumbers.NONE, ConfidentialMarking.NONE);
    BundleSection root = BundleSection.builder("Case file")
        .section(BundleSection.builder("Evidence")
            .document(doc("cover", "Cover letter", "ref-cover"))
            .document(doc("report", "Expert report", "ref-report"))
            .document(RenderTestSupport.mediaDoc("tape", "Hearing recording", "audio/mpeg")).build())
        .build();
    BundleRequest request = BundleRequest.builder()
        .externalId(UUID.randomUUID()).title("Watermarked bundle").fileName("watermarked.pdf")
        .presentation(presentation.withWatermark(new WatermarkPreset("hmcts", WatermarkPreset.Scope.ALL_PAGES,
            WatermarkPreset.Rendering.TRANSLUCENT)))
        .root(root).build();
    BundleRequest unwatermarked = BundleRequest.builder()
        .externalId(UUID.randomUUID()).title("Watermarked bundle").fileName("watermarked.pdf")
        .presentation(presentation).root(root).build();

    List<Long> plain = imagesPerPage(renderer.render(unwatermarked, context));
    BundleResult result = watermarking.render(request, context);
    try (result; PDDocument document = RenderTestSupport.loadPdf(result)) {
      // Pages: title, index, section cover sheet, then the sources: every source page gains
      // exactly the one image over the unwatermarked render; generated pages gain nothing.
      // The media link page is generated too (last page): no watermark, no nested bookmarks.
      List<Long> watermarked = imagesPerPage(result);
      assertThat(watermarked).hasSameSizeAs(plain).hasSize(12);
      for (int page = 0; page < watermarked.size(); page++) {
        boolean sourcePage = page >= 3 && page < watermarked.size() - 1;
        assertThat(watermarked.get(page)).as("images on page %d", page + 1)
            .isEqualTo(plain.get(page) + (sourcePage ? 1 : 0));
      }
      assertThat(new PDFTextStripper().getText(document)).contains("Expert report");
      org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem recording =
          findBookmark(document.getDocumentCatalog().getDocumentOutline(), "Hearing recording");
      assertThat(recording).isNotNull();
      assertThat(recording.getFirstChild()).as("a generated page carries no nested bookmarks").isNull();
      assertThat(result.warnings()).isEmpty();
    }
    RenderTestSupport.assertNothingLeftBehind(work);
  }

  private static org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem findBookmark(
      org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode node, String title) {
    for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (title.equals(child.getTitle())) {
        return child;
      }
      var nested = findBookmark(child, title);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private static List<Long> imagesPerPage(BundleResult result) throws Exception {
    try (result; PDDocument document = RenderTestSupport.loadPdf(result)) {
      List<Long> counts = new java.util.ArrayList<>();
      for (org.apache.pdfbox.pdmodel.PDPage page : document.getPages()) {
        counts.add(countImages(page.getResources()));
      }
      return counts;
    }
  }

  private static long countImages(org.apache.pdfbox.pdmodel.PDResources resources) throws Exception {
    long count = 0;
    for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
      if (resources.isImageXObject(name)) {
        count++;
      } else if (resources.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
        count += countImages(form.getResources());
      }
    }
    return count;
  }

  @Test
  void detectedTypeWinsOverALyingDeclarationWithAWarning() throws Exception {
    resolver.source("mislabelled", RenderTestSupport.Source.of(onePagePdf, "image/png", "actually-a-pdf.png"));
    // A real PNG whose tEXt chunk carries %PDF- bytes: the anchored PNG signature must win.
    resolver.source("commented", RenderTestSupport.Source.of(
        pngWithTextChunk("%PDF-1.4 embedded in a comment"), "image/png", "scan.png"));

    try (BundleResult result = renderer.render(
        request(doc("d1", "Mislabelled", "mislabelled"), doc("d2", "Commented scan", "commented")), context)) {
      assertThat(result.documents()).extracting(DocumentResult::mediaType)
          .containsExactly("application/pdf", "image/png");
      // The PNG scan also earns the no-text warning; the mismatch is the one under test.
      List<BundleWarning> mismatches = result.warnings().stream()
          .filter(w -> !w.code().equals(DefaultBundleRenderer.WARNING_NO_EXTRACTABLE_TEXT)).toList();
      assertThat(mismatches).singleElement().satisfies(warning -> {
        assertThat(warning.code()).isEqualTo(DefaultBundleRenderer.WARNING_MEDIA_TYPE_MISMATCH);
        assertThat(warning.documentId()).contains("d1");
        assertThat(warning.message()).contains("image/png").contains("application/pdf");
      });
      assertThat(result.pageCount()).isEqualTo(4);
    }
  }

  @Test
  @Timeout(30)
  void excessRendersBlockUntilAPermitFrees() throws Exception {
    CountDownLatch twoEntered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger entered = new AtomicInteger();
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();
    BundlingExtension gatedPdfHandler = new BundlingExtension() {
      @Override
      public String name() {
        return "gated-pdf";
      }

      @Override
      public void configure(BundlingExtensionContext registry) {
        registry.replaceHandler("application/pdf", (source, ctx) -> {
          entered.incrementAndGet();
          maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
          twoEntered.countDown();
          try {
            release.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            active.decrementAndGet();
          }
          return HandledDocument.of(((FileBackedSource) source).file());
        });
      }
    };
    BundleRenderer gated = BundleRenderer.builder().resolver(resolver).tempDirectory(work)
        .maxConcurrentRenders(2).extension(gatedPdfHandler).build();
    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      final List<Future<BundleResult>> futures = Stream.of("one", "two", "three")
          .map(name -> executor.submit(() -> gated.render(
              request(doc("doc-" + name, "Document " + name, "ref-cover")), context)))
          .toList();
      assertThat(twoEntered.await(15, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(500);
      assertThat(entered).as("the third render is blocked on the permit").hasValue(2);

      release.countDown();
      for (Future<BundleResult> future : futures) {
        try (BundleResult result = future.get(20, TimeUnit.SECONDS)) {
          assertThat(result.pageCount()).isEqualTo(3);
        }
      }
      assertThat(entered).hasValue(3);
      assertThat(maxActive).hasValueLessThanOrEqualTo(2);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
    RenderTestSupport.assertNothingLeftBehind(work);
  }

  // A real, decodable PNG whose first kilobyte contains {@code text} in a tEXt chunk.
  private static byte[] pngWithTextChunk(String text) {
    byte[] png = fixture("schmcts.png");
    byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
    byte[] data = ("Comment\0" + text).getBytes(StandardCharsets.US_ASCII);
    CRC32 crc = new CRC32();
    crc.update(type);
    crc.update(data);
    // Signature (8) + IHDR (4 len + 4 type + 13 data + 4 crc) = insert at offset 33.
    ByteArrayOutputStream assembled = new ByteArrayOutputStream();
    assembled.write(png, 0, 33);
    assembled.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());
    assembled.writeBytes(type);
    assembled.writeBytes(data);
    assembled.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    assembled.write(png, 33, png.length - 33);
    return assembled.toByteArray();
  }
}
