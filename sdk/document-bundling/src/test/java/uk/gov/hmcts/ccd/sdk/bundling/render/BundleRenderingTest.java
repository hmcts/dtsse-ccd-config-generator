package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.request;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.sha256;

import java.io.InputStream;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
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
      // The empty section's placeholder page is the only warning by design.
      assertThat(result.outcome()).isEqualTo(BundleOutcome.COMPLETED_WITH_WARNINGS);
      assertThat(result.warnings()).extracting(BundleWarning::code)
          .containsExactly(PdfBundleAssembler.WARNING_EMPTY_SECTION_PAGE);
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
          .containsExactly("cover", "report", "photo", "logo");
      assertThat(result.documents()).extracting(DocumentResult::mediaType)
          .containsExactly("application/pdf", "application/pdf", "image/jpeg", "image/png");
      assertThat(result.documents()).extracting(DocumentResult::sha256)
          .startsWith(sha256(onePagePdf), sha256(multiPagePdf), sha256(jpeg), sha256(png));
      assertThat(result.documents()).extracting(DocumentResult::pageCount)
          .containsExactly(1, reportPages, 1, 1);
      // p1 title, p2 contents, p3 cover, p4 Evidence cover sheet, then its documents, then the
      // empty-section placeholder.
      assertThat(result.documents()).extracting(DocumentResult::startPage)
          .containsExactly(3, 5, 5 + reportPages, 6 + reportPages);
      assertThat(result.pageCount()).isEqualTo(7 + reportPages);

      // Output PDF semantics.
      assertThat(document.getNumberOfPages()).isEqualTo(result.pageCount());
      assertThat(new PDFTextStripper().getText(document))
          .contains("Final hearing bundle", "Index Page", "Cover letter", "Expert report",
              "Correspondence", "There are no documents in this section.");
      assertThat(RenderTestSupport.outlineTitles(document))
          .contains("Evidence", "Correspondence", "Cover letter", "Expert report",
              "Site photograph", "Court logo");

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

}
