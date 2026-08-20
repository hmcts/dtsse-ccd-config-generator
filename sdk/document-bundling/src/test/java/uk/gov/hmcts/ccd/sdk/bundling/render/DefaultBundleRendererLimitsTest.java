package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRendererBuilder;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.convert.FileBackedSource;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;

/**
 * Every configurable maximum breaches with a descriptive typed error at the right stage, before
 * anything is published: document count, per-document bytes (a declared lie and an actual
 * overrun), total pages, output bytes, the office-conversion ceiling, and the hard end-to-end
 * timeout with a slow handler.
 */
class DefaultBundleRendererLimitsTest {

  private static final long MB = 1024L * 1024L;

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
            fixture("one-page.pdf"), "application/pdf", "good.pdf"))
        .source("other", RenderTestSupport.Source.of(
            fixture("one-page.pdf"), "application/pdf", "other.pdf"));
    destination = new RenderTestSupport.RecordingDestination(
        new FilesystemBundleDestination(published));
  }

  private BundleRendererBuilder builder(BundleLimits limits) {
    return BundleRenderer.builder()
        .resolver(resolver)
        .destination(destination)
        .tempDirectory(work)
        .limits(limits);
  }

  private static BundleLimits limits(
      int maxDocuments, long maxSourceBytes, long maxOfficeBytes, long maxOutputBytes,
      int maxTotalPages, Duration maxElapsed) {
    return new BundleLimits(maxDocuments, maxSourceBytes, maxOfficeBytes, maxOutputBytes,
        maxTotalPages, maxElapsed);
  }

  private BundleGenerationException expectFailure(BundleRenderer renderer,
      BundleRequest request) {
    BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
        () -> renderer.render(request, BundleExecutionContext.empty()));
    assertThat(failure).isNotNull();
    assertThat(destination.stores).hasValue(0);
    try {
      assertThat(Files.list(published)).isEmpty();
      assertThat(Files.list(work)).isEmpty();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    return failure;
  }

  private static BundleRequest twoDocumentRequest() {
    return BundleRequest.builder()
        .externalId(UUID.randomUUID())
        .title("Bundle")
        .fileName("bundle.pdf")
        .root(BundleSection.builder("Case file")
            .document(RenderTestSupport.doc("d1", "First", "good"))
            .document(RenderTestSupport.doc("d2", "Second", "other"))
            .build())
        .build();
  }

  @Test
  void documentCountLimitFailsValidation() {
    BundleRenderer renderer =
        builder(limits(1, 300 * MB, 50 * MB, 1024 * MB, 1000, Duration.ofMinutes(1))).build();

    BundleGenerationException failure = expectFailure(renderer, twoDocumentRequest());

    assertThat(failure.code()).isEqualTo(BundleErrorCode.LIMIT_EXCEEDED);
    assertThat(failure.stage()).isEqualTo(BundleStage.VALIDATE);
    assertThat(failure.getMessage()).contains("2 documents").contains("maximum of 1");
  }

  @Test
  void aDeclaredLengthAboveTheLimitFailsBeforeReading() {
    resolver.source("liar", new RenderTestSupport.Source(
        fixture("one-page.pdf"), "application/pdf", "liar.pdf",
        OptionalLong.of(400 * MB)));
    BundleRenderer renderer =
        builder(limits(100, 1000, 50 * MB, 1024 * MB, 1000, Duration.ofMinutes(1))).build();

    BundleGenerationException failure = expectFailure(renderer,
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Liar", "liar")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.LIMIT_EXCEEDED);
    assertThat(failure.stage()).isEqualTo(BundleStage.RESOLVE);
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(doc -> assertThat(doc.detail()).contains("declares"));
  }

  @Test
  void anActualOverrunDuringTheCopyFailsTheDocument() {
    byte[] big = new byte[5000];
    resolver.source("big", new RenderTestSupport.Source(
        big, "application/pdf", "big.pdf", OptionalLong.empty()));
    BundleRenderer renderer =
        builder(limits(100, 1000, 50 * MB, 1024 * MB, 1000, Duration.ofMinutes(1))).build();

    BundleGenerationException failure = expectFailure(renderer,
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Big", "big")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.LIMIT_EXCEEDED);
    assertThat(failure.stage()).isEqualTo(BundleStage.RESOLVE);
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(doc -> assertThat(doc.detail()).contains("during transfer"));
  }

  @Test
  void accumulatedSourcePagesBreachTheTotalPageLimitBeforeAssembly() {
    BundleRenderer renderer =
        builder(limits(100, 300 * MB, 50 * MB, 1024 * MB, 1, Duration.ofMinutes(1))).build();

    BundleGenerationException failure = expectFailure(renderer, twoDocumentRequest());

    assertThat(failure.code()).isEqualTo(BundleErrorCode.LIMIT_EXCEEDED);
    assertThat(failure.stage()).isEqualTo(BundleStage.INSPECT);
    assertThat(failure.getMessage()).contains("maximum of 1 pages");
  }

  @Test
  void outputByteLimitFailsBeforeTheDestinationIsCalled() {
    BundleRenderer renderer =
        builder(limits(100, 300 * MB, 50 * MB, 1000, 1000, Duration.ofMinutes(1))).build();

    BundleGenerationException failure = expectFailure(renderer,
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Doc", "good")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.LIMIT_EXCEEDED);
    assertThat(failure.stage()).isEqualTo(BundleStage.STORE);
    assertThat(failure.getMessage()).contains("1000 bytes").contains("nothing was published");
  }

  @Test
  void theOfficeConversionCeilingIsEnforcedBeforeCallingDocmosis() {
    resolver.source("word", RenderTestSupport.Source.of(
        fixture("wordDocument.doc"), "application/msword", "wordDocument.doc"));
    DocmosisRenderService neverCalled = new DocmosisRenderService() {
      @Override
      public Path convertToPdf(Path source, String fileName, String mediaType) {
        throw new AssertionError("Docmosis must not be called for an oversized source");
      }

      @Override
      public Path renderTemplate(String templateName,
          java.util.Map<String, Object> payload) {
        throw new UnsupportedOperationException();
      }
    };
    BundleRenderer renderer =
        builder(limits(100, 300 * MB, 10, 1024 * MB, 1000, Duration.ofMinutes(1)))
            .docmosis(neverCalled)
            .build();

    BundleGenerationException failure = expectFailure(renderer,
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Word", "word")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONVERSION_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.CONVERT);
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(doc -> assertThat(doc.detail())
            .contains("maxOfficeSourceBytesPerDocument"));
  }

  @Test
  void theHardTimeoutFailsTypedWithTimingsAndStillCleansUp() {
    BundlingExtension slowPdfHandler = new BundlingExtension() {
      @Override
      public String name() {
        return "slow-pdf";
      }

      @Override
      public void configure(BundlingExtensionContext context) {
        context.replaceHandler("application/pdf", (source, ctx) -> {
          try {
            Thread.sleep(600);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentHandlingException("interrupted", e);
          }
          return HandledDocument.of(((FileBackedSource) source).file());
        });
      }
    };
    BundleRenderer renderer =
        builder(limits(100, 300 * MB, 50 * MB, 1024 * MB, 1000, Duration.ofMillis(300)))
            .extension(slowPdfHandler)
            .build();

    BundleGenerationException failure = expectFailure(renderer,
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Slow", "good")));

    assertThat(failure.code()).isEqualTo(BundleErrorCode.TIMED_OUT);
    assertThat(failure.stage()).isEqualTo(BundleStage.CONVERT);
    assertThat(failure.getMessage())
        .contains("Stage timings so far")
        .contains("VALIDATE")
        .contains("Nothing was published");
  }
}
