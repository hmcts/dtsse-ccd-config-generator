package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRendererBuilder;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStorageException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;

/**
 * Every failure mode in the design's failure-semantics table: the typed code, the stage, the
 * document attribution — and that NOTHING was published (the destination is never called and no
 * file appears).
 */
class DefaultBundleRendererFailureTest {

  @TempDir
  Path work;

  @TempDir
  Path published;

  private RenderTestSupport.InMemoryResolver resolver;
  private RenderTestSupport.RecordingDestination destination;

  @BeforeEach
  void setUp() {
    resolver = new RenderTestSupport.InMemoryResolver()
        .source("good", RenderTestSupport.Source.of(
            fixture("one-page.pdf"), "application/pdf", "good.pdf"));
    destination = new RenderTestSupport.RecordingDestination(
        new FilesystemBundleDestination(published));
  }

  private BundleRenderer renderer() {
    return builder().build();
  }

  private BundleRendererBuilder builder() {
    return BundleRenderer.builder()
        .resolver(resolver)
        .destination(destination)
        .tempDirectory(work);
  }

  private BundleGenerationException renderExpectingFailure(
      BundleRenderer renderer, BundleRequest request) {
    BundleGenerationException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(request, BundleExecutionContext.empty()));
    assertThat(failure).isNotNull();
    assertNothingPublished();
    return failure;
  }

  private void assertNothingPublished() {
    assertThat(destination.stores).hasValue(0);
    try {
      assertThat(Files.list(published)).isEmpty();
      assertThat(Files.list(work)).isEmpty();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void mediaDocumentWithoutAMediaTypeFailsValidation() {
    BundleDocument media = BundleDocument.builder()
        .id("m1")
        .title("Recording")
        .reference(new DocumentReference(RenderTestSupport.PROVIDER, "m1"))
        .media(MediaPlaceholder.builder()
            .accessUrl("https://media.example.net/recordings/m1")
            .build())
        .build();

    BundleGenerationException failure = renderExpectingFailure(
        renderer(), RenderTestSupport.singleDocumentRequest(media));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.REQUEST_INVALID);
    assertThat(failure.stage()).isEqualTo(BundleStage.VALIDATE);
    assertThat(failure.documentFailures()).singleElement().satisfies(document -> {
      assertThat(document.documentId()).isEqualTo("m1");
      assertThat(document.detail()).contains("mediaType");
    });
  }

  @Test
  void unregisteredMediaTypeFailsValidationNamingTheRegisteredTypes() {
    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(
            RenderTestSupport.mediaDoc("m1", "Recording", "audio/wav")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.MEDIA_TYPE_UNSUPPORTED);
    assertThat(failure.stage()).isEqualTo(BundleStage.VALIDATE);
    assertThat(failure.getMessage())
        .contains("audio/wav")
        .contains("m1")
        .contains("application/pdf")   // the registered types are named
        .contains("audio/mpeg");
  }

  @Test
  void unknownResolverProviderFailsNamingTheProvider() {
    BundleDocument document = BundleDocument.builder()
        .id("d1")
        .title("Order")
        .reference(new DocumentReference("some-other-provider", "x"))
        .build();

    BundleGenerationException failure = renderExpectingFailure(
        renderer(), RenderTestSupport.singleDocumentRequest(document));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.RESOLVE);
    assertThat(failure.getMessage())
        .contains("some-other-provider")
        .contains(RenderTestSupport.PROVIDER);
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(doc -> assertThat(doc.documentId()).isEqualTo("d1"));
  }

  @Test
  void everyFailedReferenceIsNamedInOneResolutionFailure() {
    resolver
        .failure("missing", new ResolutionFailure(
            ResolutionFailureReason.NOT_FOUND, "No document with this id"))
        .failure("denied", new ResolutionFailure(
            ResolutionFailureReason.ACCESS_DENIED, "The system user may not read this"))
        .failure("flaky", new ResolutionFailure(
            ResolutionFailureReason.TRANSIENT_FAILURE, "Timed out downstream"));
    BundleRequest request = BundleRequest.builder()
        .externalId(UUID.randomUUID())
        .title("Bundle")
        .fileName("bundle.pdf")
        .root(BundleSection.builder("Case file")
            .document(RenderTestSupport.doc("d1", "Fine", "good"))
            .document(RenderTestSupport.doc("d2", "Missing", "missing"))
            .document(RenderTestSupport.doc("d3", "Denied", "denied"))
            .document(RenderTestSupport.doc("d4", "Flaky", "flaky"))
            .build())
        .build();

    BundleGenerationException failure = renderExpectingFailure(renderer(), request);

    // Mixed reasons aggregate under the general resolution code, each document typed.
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.RESOLVE);
    assertThat(failure.documentFailures()).hasSize(3);
    assertThat(failure.documentFailures()).extracting(f -> f.documentId())
        .containsExactly("d2", "d3", "d4");
    assertThat(failure.documentFailures()).extracting(f -> f.code())
        .containsExactly(BundleErrorCode.DOCUMENT_NOT_FOUND,
            BundleErrorCode.DOCUMENT_ACCESS_DENIED,
            BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    assertThat(failure.getMessage()).contains("d2").contains("d3").contains("d4");
  }

  @Test
  void aUniformFailureReasonBecomesTheTopLevelCode() {
    resolver.failure("missing", new ResolutionFailure(
        ResolutionFailureReason.NOT_FOUND, "No document with this id"));

    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(
            RenderTestSupport.doc("d1", "Missing", "missing")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_NOT_FOUND);
    assertThat(failure.stage()).isEqualTo(BundleStage.RESOLVE);
  }

  @Test
  void accessDeniedIsItsOwnCode() {
    resolver.failure("denied", new ResolutionFailure(
        ResolutionFailureReason.ACCESS_DENIED, "Not permitted"));

    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(
            RenderTestSupport.doc("d1", "Denied", "denied")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_ACCESS_DENIED);
  }

  @Test
  void aReferenceWithNoResolverOutcomeFailsTyped() {
    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(
            RenderTestSupport.doc("d1", "Forgotten", "not-configured")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(doc -> assertThat(doc.detail()).contains("no outcome"));
  }

  @Test
  void aThrowingResolverFailsItsWholeBatchTyped() {
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(new uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver() {
          @Override
          public String provider() {
            return RenderTestSupport.PROVIDER;
          }

          @Override
          public uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments resolveAll(
              List<DocumentReference> references, BundleExecutionContext context) {
            throw new IllegalStateException("downstream blew up");
          }
        })
        .destination(destination)
        .tempDirectory(work)
        .build();

    BundleGenerationException failure = renderExpectingFailure(
        renderer,
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Doc", "good")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(doc -> assertThat(doc.detail()).contains("IllegalStateException"));
  }

  @Test
  void anEncryptedPdfFailsConversionNamingTheDocument() throws Exception {
    resolver.source("locked", RenderTestSupport.Source.of(
        encryptedPdf(), "application/pdf", "locked.pdf"));

    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(
            RenderTestSupport.doc("d1", "Locked", "locked")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONVERSION_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.CONVERT);
    assertThat(failure.documentFailures()).singleElement().satisfies(doc -> {
      assertThat(doc.documentId()).isEqualTo("d1");
      assertThat(doc.detail()).containsIgnoringCase("encrypted");
    });
  }

  @Test
  void aBrokenPdfFailsConversionNamingTheDocument() {
    resolver.source("broken", RenderTestSupport.Source.of(
        "%PDF-1.7\nthis is not really a pdf".getBytes(StandardCharsets.UTF_8),
        "application/pdf", "broken.pdf"));

    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(
            RenderTestSupport.doc("d1", "Broken", "broken")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONVERSION_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.CONVERT);
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(doc -> assertThat(doc.documentId()).isEqualTo("d1"));
  }

  @Test
  void declaredPdfDetectedAsNonOfficeZipFailsContentInvalidNamingBothTypes() {
    byte[] zip = {'P', 'K', 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    resolver.source("zip", new RenderTestSupport.Source(
        zip, "application/pdf", "archive.pdf", java.util.OptionalLong.of(zip.length)));

    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Zip", "zip")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONTENT_INVALID);
    assertThat(failure.stage()).isEqualTo(BundleStage.CONVERT);
    assertThat(failure.getMessage()).contains("application/pdf").contains("ZIP");
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(doc -> assertThat(doc.documentId()).isEqualTo("d1"));
  }

  @Test
  void anUnhandledMediaTypeFailsNamingTheRegisteredTypes() {
    // A type nothing handles by default (and no extension registers).
    resolver.source("msg", RenderTestSupport.Source.of(
        "plain unsignatured bytes".getBytes(StandardCharsets.UTF_8),
        "application/vnd.ms-outlook", "mail.msg"));

    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Mail", "msg")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.MEDIA_TYPE_UNSUPPORTED);
    assertThat(failure.stage()).isEqualTo(BundleStage.CONVERT);
    assertThat(failure.getMessage())
        .contains("application/vnd.ms-outlook")
        .contains("d1")
        .contains("application/pdf");
  }

  @Test
  void anOfficeDocumentWithoutDocmosisFailsWithItsDedicatedCode() {
    // text/plain is an office type: without Docmosis the failure names Docmosis and the
    // properties to set, not a generic unsupported-type error.
    resolver.source("notes", RenderTestSupport.Source.of(
        "just some notes".getBytes(StandardCharsets.UTF_8), "text/plain", "notes.txt"));

    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Notes", "notes")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCMOSIS_NOT_CONFIGURED);
    assertThat(failure.stage()).isEqualTo(BundleStage.CONVERT);
    assertThat(failure.getMessage())
        .contains("text/plain")
        .contains("d1")
        .contains("ccd.bundling.docmosis");
  }

  @Test
  void mediaContentResolvedAsBytesFailsContentInvalid() {
    byte[] mp3 = "ID3rest-of-an-mp3".getBytes(StandardCharsets.UTF_8);
    resolver.source("song", RenderTestSupport.Source.of(mp3, "application/pdf", "song.pdf"));

    BundleGenerationException failure = renderExpectingFailure(
        renderer(),
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Song", "song")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONTENT_INVALID);
    assertThat(failure.getMessage()).contains("MediaPlaceholder");
  }

  @Test
  void aTransientDocmosisFailureFailsConversionWithTheTransientDetail() {
    resolver.source("word", RenderTestSupport.Source.of(
        fixture("wordDocument.doc"), "application/msword", "wordDocument.doc"));
    DocmosisRenderService failing = new DocmosisRenderService() {
      @Override
      public Path convertToPdf(Path source, String fileName, String mediaType)
          throws DocmosisRenderException {
        throw new DocmosisRenderException("Docmosis returned HTTP 503", true);
      }

      @Override
      public Path renderTemplate(String templateName, Map<String, Object> payload) {
        throw new UnsupportedOperationException();
      }
    };

    BundleGenerationException failure = renderExpectingFailure(
        builder().docmosis(failing).build(),
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Word", "word")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONVERSION_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.CONVERT);
    assertThat(failure.documentFailures()).singleElement().satisfies(doc -> {
      assertThat(doc.detail()).contains("transiently").contains("HTTP 503");
    });
  }

  @Test
  void aHandlerProducingAnEncryptedPdfFailsInspection() {
    BundleRenderer renderer = builder()
        .extension(replacePdfHandler((source, context) -> {
          try {
            Path out = context.createTempFile(".pdf");
            Files.write(out, encryptedPdf());
            return HandledDocument.of(out);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }))
        .build();

    BundleGenerationException failure = renderExpectingFailure(
        renderer,
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Doc", "good")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_INSPECTION_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.INSPECT);
    assertThat(failure.documentFailures()).singleElement().satisfies(doc -> {
      assertThat(doc.documentId()).isEqualTo("d1");
      assertThat(doc.detail()).containsIgnoringCase("encrypted");
    });
  }

  @Test
  void aFailingDestinationFailsStorageAndLeavesNoFile() {
    RenderTestSupport.RecordingDestination failing = new RenderTestSupport.RecordingDestination(
        (artifact, context) -> {
          throw new IllegalStateException("CDAM is down");
        });
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver)
        .destination(failing)
        .tempDirectory(work)
        .build();

    BundleGenerationException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Doc", "good")),
            BundleExecutionContext.empty()));
    assertThat(failure).isNotNull();

    assertThat(failure.code()).isEqualTo(BundleErrorCode.STORAGE_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.STORE);
    // The destination was attempted once, published nothing, and the temp dir is gone.
    assertThat(failing.stores).hasValue(1);
    try {
      assertThat(Files.list(published)).isEmpty();
      assertThat(Files.list(work)).isEmpty();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void aPermanentDestinationRejectionFailsAsStorageRejected() {
    RenderTestSupport.RecordingDestination rejecting = new RenderTestSupport.RecordingDestination(
        (artifact, context) -> {
          throw new BundleStorageException("CDAM rejected the upload with HTTP status 403", true);
        });
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver)
        .destination(rejecting)
        .tempDirectory(work)
        .build();

    BundleGenerationException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Doc", "good")),
            BundleExecutionContext.empty()));
    assertThat(failure).isNotNull();

    assertThat(failure.code()).isEqualTo(BundleErrorCode.STORAGE_REJECTED);
    assertThat(failure.stage()).isEqualTo(BundleStage.STORE);
    assertThat(failure.getMessage()).contains("403");
  }

  @Test
  void aTransientStorageExceptionStillFailsAsStorageFailed() {
    RenderTestSupport.RecordingDestination failing = new RenderTestSupport.RecordingDestination(
        (artifact, context) -> {
          throw new BundleStorageException("CDAM upload failed with HTTP status 503", false);
        });
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver)
        .destination(failing)
        .tempDirectory(work)
        .build();

    BundleGenerationException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Doc", "good")),
            BundleExecutionContext.empty()));
    assertThat(failure).isNotNull();

    assertThat(failure.code()).isEqualTo(BundleErrorCode.STORAGE_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.STORE);
  }

  private static BundlingExtension replacePdfHandler(DocumentHandler handler) {
    return new BundlingExtension() {
      @Override
      public String name() {
        return "test-pdf-replacement";
      }

      @Override
      public void configure(BundlingExtensionContext context) {
        context.replaceHandler("application/pdf", handler);
      }
    };
  }

  private static byte[] encryptedPdf() throws IOException {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      // An empty user password: PDFBox opens it silently, but isEncrypted() reports the truth.
      document.protect(new StandardProtectionPolicy("owner-pass", "",
          new AccessPermission()));
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }
}
