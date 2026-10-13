package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason.ACCESS_DENIED;
import static uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason.NOT_FOUND;
import static uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason.TRANSIENT_FAILURE;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocumentPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocumentReason;
import uk.gov.hmcts.ccd.sdk.bundling.api.RenderAttempt;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

class MissingDocumentPlaceholderTest {

  @TempDir
  Path work;

  private RenderTestSupport.InMemoryResolver resolver;
  private BundleRenderer renderer;

  @BeforeEach
  void setUp() {
    resolver = new RenderTestSupport.InMemoryResolver()
        .source("good", RenderTestSupport.Source.of(fixture("one-page.pdf"), "application/pdf", "good.pdf"))
        .source("broken", RenderTestSupport.Source.of(
            "%PDF-1.7\nthis is not really a pdf".getBytes(StandardCharsets.UTF_8), "application/pdf",
            "broken.pdf"))
        .source("msg", RenderTestSupport.Source.of(
            "hello".getBytes(StandardCharsets.US_ASCII), "application/vnd.ms-outlook", "mail.msg"))
        .source("word", RenderTestSupport.Source.of(fixture("wordDocument.doc"), "application/msword", "w.doc"))
        .failure("deleted", NOT_FOUND, "No such document")
        .failure("denied", ACCESS_DENIED, "Not permitted")
        .failure("flaky", TRANSIENT_FAILURE, "Timed out");
    renderer = BundleRenderer.builder().resolver(resolver).tempDirectory(work).build();
  }

  @Test
  void documentsThatCannotBeFetchedAreReplacedByPlaceholderPages() throws IOException {
    BundleRequest request = placeholderRequest(doc("d1", "Claim form", "good"),
        doc("d2", "Witness statement", "deleted"), doc("d3", "Tenancy agreement", "denied"));

    try (BundleResult result = renderer.render(request, BundleExecutionContext.empty())) {
      assertThat(result.documents()).extracting(DocumentResult::documentId).containsExactly("d1");
      assertThat(result.missingDocuments()).extracting(MissingDocument::documentId, MissingDocument::reason,
              MissingDocument::code)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("d2", MissingDocumentReason.NOT_FOUND,
                  BundleErrorCode.DOCUMENT_NOT_FOUND),
              org.assertj.core.groups.Tuple.tuple("d3", MissingDocumentReason.ACCESS_DENIED,
                  BundleErrorCode.DOCUMENT_ACCESS_DENIED));
      assertThat(result.missingDocuments()).first().extracting(MissingDocument::detail)
          .isEqualTo("No such document");
      assertThat(result.outcome()).isEqualTo(BundleOutcome.COMPLETED_WITH_WARNINGS);
      assertThat(result.warnings())
          .filteredOn(warning -> warning.code().equals(DefaultBundleRenderer.WARNING_DOCUMENT_MISSING))
          .extracting(warning -> warning.message())
          .hasSize(2)
          .allSatisfy(message -> assertThat(message).doesNotContain("No such document", "Not permitted"))
          .first().asString().contains(MissingDocumentReason.NOT_FOUND.message());

      try (PDDocument pdf = RenderTestSupport.loadPdf(result)) {
        MissingDocument deleted = result.missingDocuments().getFirst();
        assertThat(pageText(pdf, deleted.startPage()))
            .contains("Witness statement (missing)")
            .contains("This document is missing from the bundle")
            .contains(MissingDocumentReason.NOT_FOUND.message())
            .doesNotContain("No such document");
        assertThat(RenderTestSupport.outlineTitles(pdf))
            .contains("Claim form", "Witness statement (missing)", "Tenancy agreement (missing)");
      }
    }
  }

  @Test
  void missingReportCarriesSelectedTitlesAndNestedSectionPaths() throws Exception {
    BundleRequest request = BundleRequest.builder().externalId(UUID.randomUUID()).title("Bundle")
        .fileName("bundle.pdf").missingDocuments(MissingDocumentPolicy.PLACEHOLDER)
        .root(BundleSection.builder("Root")
            .document(doc("root", "Root witness", "deleted"))
            .section(BundleSection.builder("Evidence")
                .section(BundleSection.builder("Witnesses")
                    .document(doc("nested", "Selected witness name", "deleted")).build()).build())
            .build()).build();
    try (BundleResult result = renderer.render(request, BundleExecutionContext.empty())) {
      assertThat(result.missingDocuments()).extracting(MissingDocument::title)
          .containsExactly("Root witness", "Selected witness name");
      assertThat(result.missingDocuments().get(0).sectionPath()).isEmpty();
      assertThat(result.missingDocuments().get(1).sectionPath()).containsExactly("Evidence", "Witnesses");
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      MissingDocument snapshot = result.missingDocuments().get(1);
      assertThat(mapper.readValue(mapper.writeValueAsString(snapshot), MissingDocument.class)).isEqualTo(snapshot);
      var legacy = mapper.valueToTree(snapshot);
      ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove(java.util.List.of("title", "sectionPath"));
      MissingDocument restored = mapper.treeToValue(legacy, MissingDocument.class);
      assertThat(restored.title()).isEqualTo(snapshot.documentId());
      assertThat(restored.sectionPath()).isEmpty();
    }
  }

  @Test
  void documentsThatCannotBeConvertedOrOpenedAreReplacedOnTheFinalAttempt() {
    BundleRequest request = placeholderRequest(doc("d1", "Claim form", "good"), doc("d2", "Broken", "broken"),
        doc("d3", "Email", "msg"));

    try (BundleResult result = renderer.render(request, BundleExecutionContext.empty())) {
      assertThat(result.missingDocuments()).extracting(MissingDocument::documentId, MissingDocument::reason)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("d2", MissingDocumentReason.CONVERSION_FAILED),
              org.assertj.core.groups.Tuple.tuple("d3", MissingDocumentReason.UNSUPPORTED_FORMAT));
    }
  }

  @Test
  void aPermanentFailureIsReplacedEvenWhenTheCallerWillRetry() {
    BundleRequest request = placeholderRequest(doc("d1", "Claim form", "good"), doc("d2", "Email", "msg"),
        doc("d3", "Gone", "deleted"));

    try (BundleResult result = renderer.render(request, BundleExecutionContext.empty(), RenderAttempt.RETRYABLE)) {
      assertThat(result.missingDocuments()).extracting(MissingDocument::documentId).containsExactly("d2", "d3");
    }
  }

  @Test
  void aTemporarilyUnavailableDocumentFailsARetryableAttempt() {
    BundleRequest request = placeholderRequest(doc("d1", "Claim form", "good"), doc("d2", "Flaky", "flaky"),
        doc("d3", "Gone", "deleted"));

    BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
        () -> renderer.render(request, BundleExecutionContext.empty(), RenderAttempt.RETRYABLE));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    RenderTestSupport.assertNothingLeftBehind(work);
  }

  @Test
  void aConversionFailureThatMightBeTransientFailsARetryableAttempt() {
    BundleRequest request = placeholderRequest(doc("d1", "Broken", "broken"));

    BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
        () -> renderer.render(request, BundleExecutionContext.empty(), RenderAttempt.RETRYABLE));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONVERSION_FAILED);
  }

  @Test
  void aTemporarilyUnavailableDocumentIsReplacedOnTheFinalAttempt() {
    BundleRequest request = placeholderRequest(doc("d1", "Claim form", "good"), doc("d2", "Flaky", "flaky"));

    try (BundleResult result = renderer.render(request, BundleExecutionContext.empty(), RenderAttempt.FINAL)) {
      assertThat(result.missingDocuments()).singleElement().satisfies(missing -> {
        assertThat(missing.documentId()).isEqualTo("d2");
        assertThat(missing.reason()).isEqualTo(MissingDocumentReason.UNAVAILABLE);
      });
    }
  }

  @Test
  void aDocumentDocmosisCouldNotConvertBecauseItWasDownIsUnavailableNotUnconvertible() {
    BundleRequest request = placeholderRequest(doc("d1", "Claim form", "good"), doc("d2", "Witness statement", "word"));

    try (BundleResult result = withDocmosis(new DocmosisRenderException("Docmosis returned HTTP 503", true))
        .render(request, BundleExecutionContext.empty(), RenderAttempt.FINAL)) {
      assertThat(result.missingDocuments()).singleElement().satisfies(missing -> {
        assertThat(missing.reason()).isEqualTo(MissingDocumentReason.UNAVAILABLE);
        assertThat(missing.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONVERSION_FAILED);
      });
    }
  }

  @Test
  void aDocumentDocmosisRejectedCouldNotBeConverted() {
    BundleRequest request = placeholderRequest(doc("d1", "Witness statement", "word"));

    try (BundleResult result = withDocmosis(new DocmosisRenderException("Docmosis returned HTTP 400", false))
        .render(request, BundleExecutionContext.empty(), RenderAttempt.RETRYABLE)) {
      assertThat(result.missingDocuments()).extracting(MissingDocument::reason)
          .containsExactly(MissingDocumentReason.CONVERSION_FAILED);
    }
  }

  @Test
  void anOfficeDocumentWithoutDocmosisConfiguredStillFailsTheBundle() {
    BundleRequest request = placeholderRequest(doc("d1", "Claim form", "good"), doc("d2", "Witness statement", "word"));

    BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
        () -> renderer.render(request, BundleExecutionContext.empty(), RenderAttempt.FINAL));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCMOSIS_NOT_CONFIGURED);
  }

  @Test
  void anOfficeDocumentWithDocmosisConfiguredOnlyForTemplatesStillFailsTheBundle() {
    DocmosisRenderService templatesOnly = new DocmosisRenderService() {
      @Override
      public Path convertToPdf(Path source, String fileName, String mediaType) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean convertsFiles() {
        return false;
      }
    };
    BundleRenderer templatesOnlyRenderer =
        BundleRenderer.builder().resolver(resolver).tempDirectory(work).docmosis(templatesOnly).build();
    BundleRequest request = placeholderRequest(doc("d1", "Witness statement", "word"));

    BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
        () -> templatesOnlyRenderer.render(request, BundleExecutionContext.empty(), RenderAttempt.FINAL));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCMOSIS_NOT_CONFIGURED);
  }

  @Test
  void aBundleOfOnlyMissingDocumentsStillRenders() {
    BundleRequest request = placeholderRequest(doc("d1", "Gone", "deleted"));

    try (BundleResult result = renderer.render(request, BundleExecutionContext.empty())) {
      assertThat(result.documents()).isEmpty();
      assertThat(result.missingDocuments()).hasSize(1);
      assertThat(result.pageCount()).isPositive();
    }
  }

  @Test
  void theDefaultPolicyStillFailsTheWholeBundle() {
    BundleRequest request = RenderTestSupport.request(doc("d1", "Claim form", "good"), doc("d2", "Gone", "deleted"));

    BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
        () -> renderer.render(request, BundleExecutionContext.empty()));

    assertThat(request.missingDocuments()).isEqualTo(MissingDocumentPolicy.FAIL);
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_NOT_FOUND);
  }

  private BundleRenderer withDocmosis(DocmosisRenderException failure) {
    DocmosisRenderService docmosis = (source, fileName, mediaType) -> {
      throw failure;
    };
    return BundleRenderer.builder().resolver(resolver).tempDirectory(work).docmosis(docmosis).build();
  }

  private static BundleRequest placeholderRequest(BundleDocument... documents) {
    BundleSection.Builder root = BundleSection.builder("Case file");
    for (BundleDocument document : documents) {
      root.document(document);
    }
    return BundleRequest.builder().externalId(UUID.randomUUID()).title("Test bundle")
        .fileName("test-bundle.pdf").root(root.build())
        .missingDocuments(MissingDocumentPolicy.PLACEHOLDER).build();
  }

  private static String pageText(PDDocument pdf, int page) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setStartPage(page);
    stripper.setEndPage(page);
    return stripper.getText(pdf).replaceAll("\\s+", " ");
  }
}
