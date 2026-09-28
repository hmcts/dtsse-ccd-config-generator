package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason.ACCESS_DENIED;
import static uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason.NOT_FOUND;
import static uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason.TRANSIENT_FAILURE;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.request;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.OptionalLong;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRendererBuilder;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

class BundleRenderingFailureTest {
  private static final long MB = 1024L * 1024L;
  private static final byte[] TEXT = "hello".getBytes(StandardCharsets.US_ASCII);

  @TempDir
  Path work;

  private RenderTestSupport.InMemoryResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new RenderTestSupport.InMemoryResolver()
        .source("good", RenderTestSupport.Source.of(fixture("one-page.pdf"), "application/pdf", "good.pdf"))
        .source("other", RenderTestSupport.Source.of(fixture("one-page.pdf"), "application/pdf", "other.pdf"))
        .source("word", RenderTestSupport.Source.of(fixture("wordDocument.doc"), "application/msword", "w.doc"))
        .source("note", RenderTestSupport.Source.of(TEXT, "text/plain", "note.txt"));
  }

  private BundleRendererBuilder builder() {
    return BundleRenderer.builder().resolver(resolver).tempDirectory(work);
  }

  private BundleRenderer withLimits(int documents, long sourceBytes, long outputBytes, int pages) {
    return builder().limits(new BundleLimits(documents, sourceBytes, outputBytes, pages)).build();
  }

  private BundleGenerationException expectFailure(
      BundleRenderer renderer, BundleRequest request, BundleErrorCode code, BundleStage stage) {
    BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
        () -> renderer.render(request, BundleExecutionContext.empty()));
    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(code);
    assertThat(failure.stage()).isEqualTo(stage);
    assertThat(failure.getMessage()).startsWith(code + " at stage " + stage + ": ");
    RenderTestSupport.assertNothingLeftBehind(work);
    return failure;
  }

  private BundleGenerationException expectFailure(BundleRequest request, BundleErrorCode code, BundleStage stage) {
    return expectFailure(builder().build(), request, code, stage);
  }

  // The exception names exactly one document, with a per-document code equal to the top-level one.
  private static String detailOf(BundleGenerationException failure, String documentId) {
    assertThat(failure.documentFailures()).singleElement().satisfies(document -> {
      assertThat(document.documentId()).isEqualTo(documentId);
      assertThat(document.code()).isEqualTo(failure.code());
      assertThat(document.reference().provider()).isEqualTo(RenderTestSupport.PROVIDER);
    });
    return failure.documentFailures().get(0).detail();
  }

  @Test
  void everyFailedReferenceIsNamedInOneResolutionFailure() {
    resolver.failure("missing", NOT_FOUND, "No such document").failure("denied", ACCESS_DENIED, "Not permitted")
        .failure("flaky", TRANSIENT_FAILURE, "Timed out");

    BundleGenerationException failure = expectFailure(
        request(doc("d1", "Fine", "good"), doc("d2", "Missing", "missing"), doc("d3", "Denied", "denied"),
            doc("d4", "Flaky", "flaky")),
        BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleStage.RESOLVE);

    assertThat(failure.documentFailures()).extracting(DocumentFailure::documentId).containsExactly("d2", "d3", "d4");
    assertThat(failure.documentFailures()).extracting(DocumentFailure::code)
        .containsExactly(BundleErrorCode.DOCUMENT_NOT_FOUND, BundleErrorCode.DOCUMENT_ACCESS_DENIED,
            BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    // What failed, which documents (with their references), and what to do next, in one message.
    assertThat(failure.getMessage())
        .contains("3 of 4 unique document reference(s) could not be resolved")
        .contains("d2 (case-documents/missing): DOCUMENT_NOT_FOUND - No such document")
        .contains("d3 (case-documents/denied): DOCUMENT_ACCESS_DENIED")
        .contains("d4 (case-documents/flaky): DOCUMENT_RESOLUTION_FAILED")
        .contains("Remediation: Check the failed documents");
  }

  @Test
  void uniformFailureReasonBecomesTheTopLevelCode() {
    resolver.failure("missing", NOT_FOUND, "Gone").failure("also-missing", NOT_FOUND, "Gone")
        .failure("denied", ACCESS_DENIED, "No");

    BundleGenerationException notFound = expectFailure(
        request(doc("d1", "One", "missing"), doc("d2", "Two", "also-missing")),
        BundleErrorCode.DOCUMENT_NOT_FOUND, BundleStage.RESOLVE);
    assertThat(notFound.documentFailures()).extracting(DocumentFailure::documentId).containsExactly("d1", "d2");

    BundleGenerationException denied = expectFailure(request(doc("d3", "Three", "denied")),
        BundleErrorCode.DOCUMENT_ACCESS_DENIED, BundleStage.RESOLVE);
    assertThat(detailOf(denied, "d3")).isEqualTo("No");
  }

  @Test
  void unknownResolverProviderFailsNamingTheProvider() {
    BundleDocument foreign = BundleDocument.builder().id("d1").title("Order")
        .reference(new DocumentReference("some-other-provider", "x")).build();

    BundleGenerationException failure = expectFailure(request(foreign, doc("d2", "Fine", "good")),
        BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleStage.RESOLVE);

    assertThat(failure.getMessage()).contains("[some-other-provider]").contains("[case-documents]");
    assertThat(failure.documentFailures()).singleElement().satisfies(document -> {
      assertThat(document.documentId()).isEqualTo("d1");
      assertThat(document.detail()).contains("some-other-provider");
    });
    assertThat(resolver.batches).as("fails fast before any resolver is called").isEmpty();
  }

  @Test
  void anEncryptedOrBrokenPdfFailsConversionNamingTheDocument() throws Exception {
    resolver.source("locked", RenderTestSupport.Source.of(encryptedPdf(), "application/pdf", "locked.pdf"));
    resolver.source("broken", RenderTestSupport.Source.of(
        "%PDF-1.7\nthis is not really a pdf".getBytes(StandardCharsets.UTF_8), "application/pdf", "broken.pdf"));

    BundleGenerationException encrypted = expectFailure(
        request(doc("d1", "Fine", "good"), doc("d2", "Locked", "locked")),
        BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT);
    assertThat(detailOf(encrypted, "d2")).containsIgnoringCase("encrypted");

    BundleGenerationException broken = expectFailure(request(doc("d3", "Broken", "broken")),
        BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT);
    assertThat(detailOf(broken, "d3")).containsIgnoringCase("corrupt");

    java.io.ByteArrayOutputStream noPages = new java.io.ByteArrayOutputStream();
    try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      document.save(noPages);
    }
    resolver.source("empty", RenderTestSupport.Source.of(noPages.toByteArray(), "application/pdf", "empty.pdf"));
    BundleGenerationException empty = expectFailure(request(doc("d4", "Empty", "empty")),
        BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT);
    assertThat(detailOf(empty, "d4")).containsIgnoringCase("no pages");
  }

  @Test
  void anUnhandledMediaTypeFailsNamingTheRegisteredTypes() {
    resolver.source("msg", RenderTestSupport.Source.of(TEXT, "application/vnd.ms-outlook", "mail.msg"));

    BundleGenerationException failure = expectFailure(request(doc("d1", "Mail", "msg")),
        BundleErrorCode.MEDIA_TYPE_UNSUPPORTED, BundleStage.CONVERT);

    assertThat(failure.getMessage()).contains("'application/vnd.ms-outlook'").contains("'d1'")
        .contains("application/pdf").contains("image/png");
    assertThat(detailOf(failure, "d1")).contains("application/vnd.ms-outlook");
  }

  @Test
  void anOfficeDocumentWithoutDocmosisFailsWithItsDedicatedCode() {
    BundleGenerationException failure = expectFailure(request(doc("d1", "Word", "word")),
        BundleErrorCode.DOCMOSIS_NOT_CONFIGURED, BundleStage.CONVERT);

    assertThat(failure.getMessage()).contains("'application/msword'")
        .contains("ccd.bundling.docmosis.convert-endpoint").contains("ccd.bundling.docmosis.access-key");
    assertThat(detailOf(failure, "d1")).contains("application/msword");
  }

  @Test
  void transientDocmosisFailureFailsConversionWithTheDetail() {
    DocmosisRenderService failing = (source, fileName, mediaType) -> {
      throw new DocmosisRenderException("Docmosis returned HTTP 503", true);
    };

    BundleGenerationException failure = expectFailure(builder().docmosis(failing).build(),
        request(doc("d1", "Word", "word")), BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT);

    assertThat(detailOf(failure, "d1")).contains("Docmosis").contains("HTTP 503");
  }

  @Test
  void handlerReturningAPdfOutsideTheJobDirectoryIsRejectedTyped(@TempDir Path outside) throws Exception {
    Path foreign = outside.resolve("foreign.pdf");
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      document.save(foreign.toFile());
    }

    BundleGenerationException failure = expectFailure(
        builder().extension(textHandler("outside-jobdir", (source, ctx) -> HandledDocument.of(foreign))).build(),
        request(doc("d1", "Note", "note")), BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT);

    assertThat(detailOf(failure, "d1")).contains("outside the job");
    assertThat(foreign).as("the foreign file is never touched").exists();
  }

  @Test
  void handlerThrowingANakedRuntimeExceptionIsWrappedWithoutLeakingItsMessage() {
    DocumentHandler throwing = (source, ctx) -> {
      throw new IllegalStateException("secret internal detail with a token: abc123");
    };

    BundleGenerationException failure = expectFailure(builder().extension(textHandler("throwing", throwing)).build(),
        request(doc("d1", "Note", "note")), BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT);

    assertThat(failure.getMessage()).doesNotContain("abc123");
    assertThat(detailOf(failure, "d1")).contains("IllegalStateException").doesNotContain("abc123");
    assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void documentCountLimitFailsValidation() {
    BundleGenerationException failure = expectFailure(withLimits(1, 300 * MB, 1024 * MB, 1000),
        request(doc("d1", "First", "good"), doc("d2", "Second", "other")),
        BundleErrorCode.LIMIT_EXCEEDED, BundleStage.VALIDATE);

    assertThat(failure.getMessage()).contains("2 documents").contains("maximum of 1").contains("maxDocumentCount");
    assertThat(failure.documentFailures()).isEmpty();
    assertThat(resolver.batches).isEmpty();
  }

  @Test
  void declaredLengthAboveTheLimitFailsBeforeReading() {
    RenderTestSupport.Source liar = RenderTestSupport.Source.declaring(
        fixture("one-page.pdf"), "application/pdf", "liar.pdf", OptionalLong.of(400 * MB));
    resolver.source("liar", liar);

    BundleGenerationException failure = expectFailure(withLimits(100, 300 * MB, 1024 * MB, 1000),
        request(doc("d1", "Fine", "good"), doc("d2", "Liar", "liar")),
        BundleErrorCode.LIMIT_EXCEEDED, BundleStage.RESOLVE);

    assertThat(detailOf(failure, "d2")).contains("declares " + 400 * MB + " bytes");
    assertThat(liar.opened()).as("the stream is never read").isFalse();
  }

  @Test
  void anActualOverrunFailsDuringSpooling() {
    resolver.source("big", RenderTestSupport.Source.declaring(
        new byte[60_000], "application/pdf", "big.pdf", OptionalLong.empty()));

    BundleGenerationException failure = expectFailure(withLimits(100, 50_000, 1024 * MB, 1000),
        request(doc("d1", "Fine", "good"), doc("d2", "Big", "big")),
        BundleErrorCode.LIMIT_EXCEEDED, BundleStage.RESOLVE);

    assertThat(detailOf(failure, "d2")).contains("during transfer").contains("declared no length");
  }

  @Test
  void outputByteLimitAndTotalPageLimitFailAtAssemble() {
    BundleRequest twoDocuments = request(doc("d1", "First", "good"), doc("d2", "Second", "other"));

    BundleGenerationException bytes = expectFailure(withLimits(100, 300 * MB, 1000, 1000), twoDocuments,
        BundleErrorCode.LIMIT_EXCEEDED, BundleStage.ASSEMBLE);
    assertThat(bytes.getMessage()).contains("maximum of 1000 bytes").contains("maxOutputBytes");
    assertThat(bytes.documentFailures()).isEmpty();

    BundleGenerationException pages = expectFailure(withLimits(100, 300 * MB, 1024 * MB, 3), twoDocuments,
        BundleErrorCode.LIMIT_EXCEEDED, BundleStage.ASSEMBLE);
    assertThat(pages.getMessage()).contains("has 4 pages").contains("maximum of 3 pages").contains("maxTotalPages");
    assertThat(pages.documentFailures()).isEmpty();
  }

  private static BundlingExtension textHandler(String name, DocumentHandler handler) {
    return new BundlingExtension() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void configure(BundlingExtensionContext registry) {
        registry.addHandler("text/plain", handler);
      }
    };
  }

  private static byte[] encryptedPdf() throws IOException {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      // An empty user password: PDFBox opens it silently, but isEncrypted() reports the truth.
      document.protect(new StandardProtectionPolicy("owner-pass", "", new AccessPermission()));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }
}
