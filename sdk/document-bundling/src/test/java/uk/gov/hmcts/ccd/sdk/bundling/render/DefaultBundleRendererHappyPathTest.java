package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.PROVIDER;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.sha256;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundle;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;

/**
 * The end-to-end happy path: a multi-section bundle of real PDFs, images, and a media link page
 * rendered through {@code BundleRenderer.builder()} with an in-memory resolver, the filesystem
 * destination, and the real pdf layer, asserted field by field and semantically on the output.
 */
class DefaultBundleRendererHappyPathTest {

  @TempDir
  Path work;

  @TempDir
  Path published;

  private final byte[] onePagePdf = fixture("one-page.pdf");
  private final byte[] multiPagePdf = fixture("Potential_Energy_PDF.pdf");
  private final byte[] jpeg = fixture("flying-pig.jpg");
  private final byte[] png = fixture("schmcts.png");

  private RenderTestSupport.InMemoryResolver resolver;
  private BundleRenderer renderer;
  private BundleRequest request;
  private UUID externalId;

  @BeforeEach
  void setUp() {
    resolver = new RenderTestSupport.InMemoryResolver()
        .source("ref-cover", RenderTestSupport.Source.of(
            onePagePdf, "application/pdf", "cover-letter.pdf"))
        // Parameterised declared type: the pipeline must strip parameters before lookup.
        .source("ref-report", RenderTestSupport.Source.of(
            multiPagePdf, "application/pdf;charset=UTF-8", "report.pdf"))
        .source("ref-photo", RenderTestSupport.Source.of(jpeg, "image/jpeg", "photo.jpg"))
        .source("ref-logo", RenderTestSupport.Source.of(png, "image/png", "logo.png"));

    renderer = BundleRenderer.builder()
        .resolver(resolver)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work)
        .build();

    externalId = UUID.randomUUID();
    request = BundleRequest.builder()
        .externalId(externalId)
        .title("Final hearing bundle")
        .fileName("final-hearing-bundle.pdf")
        .root(BundleSection.builder("Case file")
            .document(RenderTestSupport.doc("cover", "Cover letter", "ref-cover"))
            .section(BundleSection.builder("Evidence")
                .document(RenderTestSupport.doc("report", "Expert report", "ref-report"))
                .document(RenderTestSupport.doc("photo", "Site photograph", "ref-photo"))
                .document(RenderTestSupport.doc("logo", "Court logo", "ref-logo"))
                .build())
            .section(BundleSection.builder("Recordings")
                .document(RenderTestSupport.mediaDoc("hearing-audio", "Hearing recording, day 2",
                    "audio/mpeg"))
                .build())
            .section(BundleSection.builder("Correspondence")
                .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER)
                .build())
            .build())
        .build();
  }

  @Test
  void rendersPublishesAndReportsTheBundle() throws Exception {
    BundleResult result = renderer.render(request, executionContext());

    // Image documents have no extractable text and the empty section adds a placeholder page,
    // so the outcome carries warnings by design.
    assertThat(result.outcome()).isEqualTo(BundleOutcome.COMPLETED_WITH_WARNINGS);
    assertThat(result.warnings())
        .extracting(warning -> warning.code())
        .contains(DefaultBundleRenderer.WARNING_NO_EXTRACTABLE_TEXT,
            PdfBundleAssembler.WARNING_EMPTY_SECTION_PAGE);

    // The published artifact: exists, and the stored facts match the file on disk.
    Path stored = published.resolve("final-hearing-bundle.pdf");
    assertThat(stored).exists();
    byte[] storedBytes = Files.readAllBytes(stored);
    assertThat(result.stored().filename()).isEqualTo("final-hearing-bundle.pdf");
    assertThat(result.stored().mediaType()).isEqualTo("application/pdf");
    assertThat(result.stored().size()).isEqualTo(storedBytes.length);
    assertThat(result.stored().sha256()).isEqualTo(sha256(storedBytes));

    // One batched fetch of the unique references.
    assertThat(resolver.batches).hasSize(1);
    assertThat(resolver.batches.get(0)).extracting(DocumentReference::id)
        .containsExactly("ref-cover", "ref-report", "ref-photo", "ref-logo");

    // The generation report, in bundle order, with detected types and source checksums.
    assertThat(result.documents()).extracting(DocumentResult::documentId)
        .containsExactly("cover", "report", "photo", "logo", "hearing-audio");
    DocumentResult cover = result.documents().get(0);
    assertThat(cover.mediaType()).isEqualTo("application/pdf");
    assertThat(cover.sha256()).isEqualTo(sha256(onePagePdf));
    assertThat(cover.pageCount()).isEqualTo(1);
    DocumentResult report = result.documents().get(1);
    assertThat(report.mediaType()).isEqualTo("application/pdf");
    int reportPages = pageCountOf(multiPagePdf);
    assertThat(report.pageCount()).isEqualTo(reportPages);
    // Exact layout: p1 title page, p2 the (single-page) contents, p3 the root document, p4 the
    // Evidence cover sheet, then the section's documents, p(7+R) the Recordings cover sheet,
    // p(8+R) the media link page, p(9+R) the empty-section placeholder. Exact values so a
    // cover-sheet off-by-one cannot pass.
    assertThat(result.documents()).extracting(DocumentResult::startPage)
        .containsExactly(3, 5, 5 + reportPages, 6 + reportPages, 8 + reportPages);
    assertThat(result.pageCount()).isEqualTo(9 + reportPages);

    // Per-stage timings cover the whole pipeline.
    assertThat(result.timings()).containsKeys(
        BundleStage.VALIDATE, BundleStage.RESOLVE, BundleStage.CONVERT,
        BundleStage.INSPECT, BundleStage.ASSEMBLE, BundleStage.STORE);

    // Output PDF semantics.
    try (PDDocument document = Loader.loadPDF(stored.toFile())) {
      assertThat(document.getNumberOfPages()).isEqualTo(result.pageCount());
      String text = new PDFTextStripper().getText(document);
      assertThat(text)
          .contains("Final hearing bundle")           // title page
          .contains("Index Page")                     // table of contents
          .contains("Cover letter")
          .contains("Expert report")
          .contains("Hearing recording, day 2")
          .contains("Media type: audio/mpeg")         // the generated media link page
          .contains("https://media.example.net/recordings/hearing-audio")
          .contains("Correspondence")
          .contains("There are no documents in this section.");
      assertThat(document.getDocumentCatalog().getDocumentOutline()).isNotNull();
    }

    // The job temp directory is gone.
    assertThat(Files.list(work)).isEmpty();

    assertCcdBundle(result);
  }

  private void assertCcdBundle(BundleResult result) {
    CcdBundle output = result.output();
    assertThat(output.getId()).isEqualTo(externalId.toString());
    assertThat(output.getTitle()).isEqualTo("Final hearing bundle");
    assertThat(output.getFileName()).isEqualTo("final-hearing-bundle.pdf");
    assertThat(output.getStitchStatus()).isEqualTo("DONE");
    assertThat(output.getDateAndTime()).isNotNull();
    assertThat(output.getStitchedDocument().getUrl()).isEqualTo(result.stored().url());
    assertThat(output.getStitchedDocument().getBinaryUrl())
        .isEqualTo(result.stored().binaryUrl());
    assertThat(output.getStitchedDocument().getFilename()).isEqualTo("final-hearing-bundle.pdf");

    // Root documents sit outside any folder; root child sections echo as folders.
    assertThat(output.getDocuments()).hasSize(1);
    assertThat(output.getDocuments().get(0).getValue().getName()).isEqualTo("Cover letter");
    assertThat(output.getDocuments().get(0).getValue().getSortIndex()).isZero();
    assertThat(output.getFolders()).extracting(folder -> folder.getValue().getName())
        .containsExactly("Evidence", "Recordings", "Correspondence");
    assertThat(output.getFolders().get(0).getValue().getDocuments())
        .extracting(value -> value.getValue().getName())
        .containsExactly("Expert report", "Site photograph", "Court logo");
    assertThat(output.getFolders().get(0).getValue().getSortIndex()).isZero();
    assertThat(output.getFolders().get(1).getValue().getSortIndex()).isEqualTo(1);

    // Presentation echoes and the wire values for the court default preset.
    assertThat(output.getHasTableOfContents()).isEqualTo(YesOrNo.YES);
    assertThat(output.getHasCoversheets()).isEqualTo(YesOrNo.NO);
    assertThat(output.getHasFolderCoversheets()).isEqualTo(YesOrNo.YES);
    assertThat(output.getPaginationStyle()).isEqualTo("bottomCenter");
    assertThat(output.getPageNumberFormat()).isEqualTo("numberOfPages");
  }

  @Test
  void ccdBundleSerialisesTheWireShape() throws Exception {
    BundleResult result = renderer.render(request, executionContext());

    ObjectMapper mapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    JsonNode json = mapper.valueToTree(result.output());

    assertThat(json.get("id").asText()).isEqualTo(externalId.toString());
    assertThat(json.get("title").asText()).isEqualTo("Final hearing bundle");
    assertThat(json.get("fileName").asText()).isEqualTo("final-hearing-bundle.pdf");
    assertThat(json.get("stitchStatus").asText()).isEqualTo("DONE");
    assertThat(json.get("stitchedDocument").get("document_url").asText())
        .isEqualTo(result.stored().url());
    assertThat(json.get("stitchedDocument").get("document_binary_url").asText())
        .isEqualTo(result.stored().binaryUrl());
    assertThat(json.get("stitchedDocument").get("document_filename").asText())
        .isEqualTo("final-hearing-bundle.pdf");
    assertThat(json.get("hasTableOfContents").asText()).isEqualTo("Yes");
    assertThat(json.get("hasCoversheets").asText()).isEqualTo("No");
    assertThat(json.get("hasFolderCoversheets").asText()).isEqualTo("Yes");
    assertThat(json.get("paginationStyle").asText()).isEqualTo("bottomCenter");
    assertThat(json.get("pageNumberFormat").asText()).isEqualTo("numberOfPages");
    JsonNode folder = json.get("folders").get(0).get("value");
    assertThat(folder.get("name").asText()).isEqualTo("Evidence");
    assertThat(folder.get("sortIndex").asInt()).isZero();
    assertThat(folder.get("documents").get(0).get("value").get("name").asText())
        .isEqualTo("Expert report");
    JsonNode rootDocument = json.get("documents").get(0).get("value");
    assertThat(rootDocument.get("name").asText()).isEqualTo("Cover letter");
    assertThat(json.get("dateAndTime").asText())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}");
  }

  @Test
  void deduplicatesIdenticalReferencesAcrossTheTree() {
    BundleRequest duplicated = BundleRequest.builder()
        .externalId(UUID.randomUUID())
        .title("Duplicated bundle")
        .fileName("duplicated.pdf")
        .root(BundleSection.builder("Case file")
            .document(RenderTestSupport.doc("first", "First placement", "ref-cover"))
            .document(RenderTestSupport.doc("second", "Second placement", "ref-cover"))
            .build())
        .build();

    BundleResult result = renderer.render(duplicated, executionContext());

    // One resolver fetch, two placements.
    assertThat(resolver.batches).hasSize(1);
    assertThat(resolver.batches.get(0)).extracting(DocumentReference::id)
        .containsExactly("ref-cover");
    assertThat(result.documents()).extracting(DocumentResult::documentId)
        .containsExactly("first", "second");
    assertThat(result.documents()).extracting(DocumentResult::sha256)
        .containsExactly(sha256(onePagePdf), sha256(onePagePdf));
    assertThat(result.documents().get(0).startPage())
        .isLessThan(result.documents().get(1).startPage());
  }

  @Test
  void aTextOnlyPdfBundleCompletesWithoutWarnings() {
    BundleDocument document = RenderTestSupport.doc("cover", "Cover letter", "ref-cover");
    BundleResult result = renderer.render(
        RenderTestSupport.singleDocumentRequest(document), executionContext());

    assertThat(result.outcome()).isEqualTo(BundleOutcome.COMPLETED);
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void detectedTypeWinsOverALyingDeclarationWithAWarning() {
    resolver.source("ref-mislabelled", RenderTestSupport.Source.of(
        jpeg, "image/png", "actually-a-jpeg.png"));
    BundleDocument document =
        RenderTestSupport.doc("mislabelled", "Mislabelled photo", "ref-mislabelled");

    BundleResult result = renderer.render(
        RenderTestSupport.singleDocumentRequest(document), executionContext());

    assertThat(result.documents().get(0).mediaType()).isEqualTo("image/jpeg");
    assertThat(result.warnings())
        .anySatisfy(warning -> {
          assertThat(warning.code())
              .isEqualTo(DefaultBundleRenderer.WARNING_MEDIA_TYPE_MISMATCH);
          assertThat(warning.documentId()).contains("mislabelled");
          assertThat(warning.message()).contains("image/png").contains("image/jpeg");
        });
  }

  private static uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext executionContext() {
    return uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext.builder()
        .caseReference("1234567890123456")
        .initiator("happy-path-test")
        .build();
  }

  private static int pageCountOf(byte[] pdf) throws java.io.IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return document.getNumberOfPages();
    }
  }
}
