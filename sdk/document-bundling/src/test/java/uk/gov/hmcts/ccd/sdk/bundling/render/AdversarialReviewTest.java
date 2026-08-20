package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;

/**
 * Adversarial review of the rendering pipeline (render + convert packages).
 *
 * <p>Each test pins either behaviour the review verified or the fix for a finding (F-n); the
 * finding reference is kept in a comment so the history of what was attacked stays legible.
 */
class AdversarialReviewTest {

  @TempDir
  Path work;

  @TempDir
  Path published;

  @TempDir
  Path outside;

  // ---------------------------------------------------------------------------------------
  // Detection policy truth table (attack area 4)
  // ---------------------------------------------------------------------------------------

  @Test
  void detectionTruthTableCornersHoldAsDocumented() {
    // declared office + ZIP signature: declared wins, no warning.
    assertThat(MediaTypes.route(docx(), zipHead(), 1024))
        .isEqualTo(new MediaTypes.Routing.Route(docx()));
    // declared office + OLE2 signature: declared wins.
    assertThat(MediaTypes.route("application/msword", ole2Head(), 1024))
        .isEqualTo(new MediaTypes.Routing.Route("application/msword"));
    // declared pdf + ZIP signature: irreconcilable.
    assertThat(MediaTypes.route("application/pdf", zipHead(), 1024))
        .isInstanceOf(MediaTypes.Routing.Irreconcilable.class);
    // declared image + OLE2 signature: irreconcilable.
    assertThat(MediaTypes.route("image/png", ole2Head(), 1024))
        .isInstanceOf(MediaTypes.Routing.Irreconcilable.class);
    // declared office + PDF signature at offset 0: detected wins with a warning.
    assertThat(MediaTypes.route(docx(), pdfHead(0), 1024))
        .isEqualTo(new MediaTypes.Routing.RouteWithMismatch(
            "application/pdf", docx(), "application/pdf"));
    // declared pdf, %PDF- after 500 bytes of scanner junk: detector and passthrough handler
    // both scan the first 1024 bytes, so they agree.
    assertThat(MediaTypes.route("application/pdf", pdfHead(500), 1024))
        .isEqualTo(new MediaTypes.Routing.Route("application/pdf"));
    // no signature at all (plain text): declared type stays in charge.
    assertThat(MediaTypes.route("application/pdf",
        "just words\n".getBytes(StandardCharsets.US_ASCII), 11))
        .isEqualTo(new MediaTypes.Routing.Route("application/pdf"));
    // zero-byte document: no signature, declared wins.
    assertThat(MediaTypes.route("application/pdf", new byte[1024], 0))
        .isEqualTo(new MediaTypes.Routing.Route("application/pdf"));
    // declared svg (no magic) + actual PNG bytes: detected PNG wins with a warning.
    assertThat(MediaTypes.route("image/svg+xml", pngHead(), 1024))
        .isEqualTo(new MediaTypes.Routing.RouteWithMismatch(
            "image/png", "image/svg+xml", "image/png"));
    // the image/jpg alias is canonically equal to detected image/jpeg: declared alias kept.
    assertThat(MediaTypes.route("image/jpg", jpegHead(), 1024))
        .isEqualTo(new MediaTypes.Routing.Route("image/jpg"));
    // declared audio/mpeg over real MP3 bytes agrees (the renderer then rejects it as
    // metadata-only media resolved as content - pinned by an existing failure test).
    assertThat(MediaTypes.route("audio/mpeg", mp3Head(), 1024))
        .isEqualTo(new MediaTypes.Routing.Route("audio/mpeg"));
    // PINNED GAP (reported, low): declared audio/mpeg over PDF content routes to the PDF
    // passthrough with only a warning - a document the request calls a recording silently
    // stitches as content. Detection winning is the documented policy; recorded here so a
    // policy change surfaces.
    assertThat(MediaTypes.route("audio/mpeg", pdfHead(0), 1024))
        .isEqualTo(new MediaTypes.Routing.RouteWithMismatch(
            "application/pdf", "audio/mpeg", "application/pdf"));
  }

  @Test
  void aZipWhoseFirstKilobyteContainsPdfBytesIsStillRoutedAsTheDeclaredOfficeType() {
    // F-1 (fixed): anchored offset-0 signatures decide before the windowed %PDF- scan.
    byte[] head = zipHead();
    // A zip local-file header carries the entry name in plain bytes at offset 30; an entry
    // named "%PDF-notes.txt" puts the PDF signature inside the detection window.
    byte[] name = "%PDF-notes.txt".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(name, 0, head, 30, name.length);

    assertThat(MediaTypes.route(docx(), head, 1024))
        .isEqualTo(new MediaTypes.Routing.Route(docx()));
  }

  @Test
  void aValidPngContainingPdfSignatureBytesStillBundles() throws Exception {
    // F-1 end-to-end (fixed): the PNG signature at offset 0 wins over %PDF- bytes in a tEXt
    // chunk, so the image handler converts it fine.
    byte[] png = pngWithTextChunk("%PDF-1.4 embedded in a comment");
    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .source("img", RenderTestSupport.Source.of(png, "image/png", "scan.png"));
    BundleRenderer renderer = builder(resolver).build();

    BundleResult result = renderer.render(
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Scan", "img")),
        BundleExecutionContext.empty());

    assertThat(result.pageCount()).isGreaterThanOrEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------
  // Typed failure guarantees (attack areas 4, 6)
  // ---------------------------------------------------------------------------------------

  @Test
  void aBlankDeclaredMediaTypeOnUndetectableContentFailsTyped() {
    // F-2 (fixed): a blank effective type fails typed MEDIA_TYPE_UNSUPPORTED before the
    // registry lookup can throw a naked IllegalArgumentException.
    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .source("txt", new RenderTestSupport.Source(
            "no signature here".getBytes(StandardCharsets.US_ASCII), null, "unknown.bin",
            OptionalLong.of(17)));
    BundleRenderer renderer = builder(resolver).build();

    assertThatThrownBy(() -> renderer.render(
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Unknown", "txt")),
        BundleExecutionContext.empty()))
        .isInstanceOf(BundleGenerationException.class)
        .hasFieldOrPropertyWithValue("code", BundleErrorCode.MEDIA_TYPE_UNSUPPORTED);
  }

  @Test
  void aMediaPlaceholderDeclaringAContentTypeStillFailsTypedNamingTheDocument() {
    // F-3 (fixed): the failure is now REQUEST_INVALID at VALIDATE; this pin only requires it
    // to stay typed and name the document, whatever the stage.
    BundleDocument media = BundleDocument.builder()
        .id("m1")
        .title("Mislabelled recording")
        .reference(new DocumentReference(RenderTestSupport.PROVIDER, "m1"))
        .media(MediaPlaceholder.builder()
            .accessUrl("https://media.example.net/recordings/m1")
            .mediaType("application/pdf")
            .build())
        .build();
    BundleRenderer renderer = builder(new RenderTestSupport.InMemoryResolver()).build();

    BundleGenerationException failure = catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(media), BundleExecutionContext.empty()));

    assertThat(failure).isNotNull();
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(f -> assertThat(f.documentId()).isEqualTo("m1"));
  }

  @Test
  void aMediaPlaceholderDeclaringAContentTypeFailsRequestValidation() {
    // F-3 (fixed): content types (PDF/image/office) on a media placeholder are rejected at
    // VALIDATE as REQUEST_INVALID, before any content is fetched.
    BundleDocument media = BundleDocument.builder()
        .id("m1")
        .title("Mislabelled recording")
        .reference(new DocumentReference(RenderTestSupport.PROVIDER, "m1"))
        .media(MediaPlaceholder.builder()
            .accessUrl("https://media.example.net/recordings/m1")
            .mediaType("application/pdf")
            .build())
        .build();
    BundleRenderer renderer = builder(new RenderTestSupport.InMemoryResolver()).build();

    BundleGenerationException failure = catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(media), BundleExecutionContext.empty()));

    assertThat(failure).isNotNull();
    assertThat(failure.stage()).isEqualTo(BundleStage.VALIDATE);
    assertThat(failure.code()).isEqualTo(BundleErrorCode.REQUEST_INVALID);
  }

  @Test
  void anOfficeDocumentWithoutDocmosisNamesDocmosisInTheError() {
    // F-5 (fixed): office types without Docmosis fail DOCMOSIS_NOT_CONFIGURED, naming the
    // ccd.bundling.docmosis.* properties and the replacement-handler alternative.
    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .source("word", RenderTestSupport.Source.of(
            fixture("wordDocument2.docx"), docx(), "statement.docx"));
    BundleRenderer renderer = builder(resolver).build(); // no .docmosis(...)

    BundleGenerationException failure = catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(
                RenderTestSupport.doc("d1", "Statement", "word")),
            BundleExecutionContext.empty()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCMOSIS_NOT_CONFIGURED);
    assertThat(failure.getMessage()).containsIgnoringCase("docmosis");
  }

  // ---------------------------------------------------------------------------------------
  // Deadline (attack area 2)
  // ---------------------------------------------------------------------------------------

  @Test
  @Timeout(30)
  void theResolveLoopStopsAtTheDeadlineBetweenDocuments() {
    // F-4 (fixed): the deadline is checked before each reference and between copy chunks while
    // spooling, so the render aborts after the first slow source instead of spooling them all.
    // A single blocking read can still overshoot by its own duration (documented).
    DocumentResolver slowResolver = new DocumentResolver() {
      @Override
      public String provider() {
        return RenderTestSupport.PROVIDER;
      }

      @Override
      public ResolvedDocuments resolveAll(
          List<DocumentReference> references, BundleExecutionContext context) {
        Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
        for (DocumentReference reference : references) {
          resolved.put(reference, slowDocument(fixture("one-page.pdf"), 300));
        }
        return new ResolvedDocuments(resolved, Map.of());
      }
    };
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(slowResolver)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work)
        .limits(shortDeadline(Duration.ofMillis(250)))
        .build();
    BundleRequest request = BundleRequest.builder()
        .externalId(UUID.randomUUID())
        .title("Slow bundle")
        .fileName("slow.pdf")
        .root(BundleSection.builder("Case file")
            .document(RenderTestSupport.doc("d1", "One", "a"))
            .document(RenderTestSupport.doc("d2", "Two", "b"))
            .document(RenderTestSupport.doc("d3", "Three", "c"))
            .build())
        .build();

    long start = System.nanoTime();
    BundleGenerationException failure = catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(request, BundleExecutionContext.empty()));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.TIMED_OUT);
    // With a checkpoint between spooled documents the render aborts after the first slow
    // source (~300ms); without one it spools all three (~900ms).
    assertThat(elapsedMillis).isLessThan(650);
  }

  @Test
  @Timeout(30)
  void aTimedOutFailureIsTypedAndCarriesCompletedStageTimingsInItsMessage() {
    // F-8 (fixed): the timings are typed data on BundleRenderTimeoutException, including the
    // in-flight stage's elapsed portion, as well as message text.
    DocumentResolver slowResolver = new DocumentResolver() {
      @Override
      public String provider() {
        return RenderTestSupport.PROVIDER;
      }

      @Override
      public ResolvedDocuments resolveAll(
          List<DocumentReference> references, BundleExecutionContext context) {
        Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
        for (DocumentReference reference : references) {
          resolved.put(reference, slowDocument(fixture("one-page.pdf"), 400));
        }
        return new ResolvedDocuments(resolved, Map.of());
      }
    };
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(slowResolver)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work)
        .limits(shortDeadline(Duration.ofMillis(150)))
        .build();

    BundleGenerationException failure = catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "One", "a")),
            BundleExecutionContext.empty()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.TIMED_OUT);
    assertThat(failure.getMessage()).contains("Stage timings so far").contains("VALIDATE");
    assertThat(failure).isInstanceOf(BundleRenderTimeoutException.class);
    Map<BundleStage, Duration> timingsSoFar =
        ((BundleRenderTimeoutException) failure).timingsSoFar();
    assertThat(timingsSoFar).containsKey(BundleStage.VALIDATE);
    // The stage in flight when the deadline fired carries its elapsed-so-far portion.
    assertThat(timingsSoFar).containsKey(BundleStage.RESOLVE);
    assertThat(timingsSoFar.get(BundleStage.RESOLVE)).isPositive();
    // Nothing published, job directory cleaned even on timeout.
    assertThat(work.toFile().list()).isEmpty();
    assertThat(published.toFile().list()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------
  // Observability (attack area 8)
  // ---------------------------------------------------------------------------------------

  @Test
  void callerOwnedMdcKeysSurviveARender() {
    // F-6 (fixed): all three MDC keys (externalId, stage, documentId) are saved and restored.
    MDC.put("stage", "caller-stage");
    MDC.put("documentId", "caller-doc");
    try {
      RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
          .source("good", RenderTestSupport.Source.of(
              fixture("one-page.pdf"), "application/pdf", "good.pdf"));
      BundleRenderer renderer = builder(resolver).build();

      renderer.render(
          RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Order", "good")),
          BundleExecutionContext.empty());

      assertThat(MDC.get("stage")).isEqualTo("caller-stage");
      assertThat(MDC.get("documentId")).isEqualTo("caller-doc");
    } finally {
      MDC.clear();
    }
  }

  @Test
  void theExternalIdMdcKeyIsRestoredAfterARender() {
    MDC.put("externalId", "caller-external-id");
    try {
      RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
          .source("good", RenderTestSupport.Source.of(
              fixture("one-page.pdf"), "application/pdf", "good.pdf"));
      BundleRenderer renderer = builder(resolver).build();

      renderer.render(
          RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Order", "good")),
          BundleExecutionContext.empty());

      assertThat(MDC.get("externalId")).isEqualTo("caller-external-id");
    } finally {
      MDC.clear();
    }
  }

  // ---------------------------------------------------------------------------------------
  // Handler SPI abuse (attack area 5)
  // ---------------------------------------------------------------------------------------

  @Test
  void aHandlerReturningAPdfOutsideTheJobDirectoryIsRejectedTyped() throws Exception {
    // F-7 (fixed): HandledDocument.pdfFile must resolve inside the job's temporary directory;
    // anything else (a file from another job, any PDF the JVM can read) is rejected typed,
    // naming the handler and the document, and nothing is published. The foreign file itself
    // is left untouched.
    Path foreign = outside.resolve("foreign.pdf");
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      document.save(foreign.toFile());
    }
    BundlingExtension hijack = new BundlingExtension() {
      @Override
      public String name() {
        return "outside-jobdir";
      }

      @Override
      public void configure(BundlingExtensionContext context) {
        context.addHandler("text/plain", (source, ctx) -> HandledDocument.of(foreign));
      }
    };
    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .source("note", RenderTestSupport.Source.of(
            "hello".getBytes(StandardCharsets.US_ASCII), "text/plain", "note.txt"));
    BundleRenderer renderer = builder(resolver).extension(hijack).build();

    BundleGenerationException failure = catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Note", "note")),
            BundleExecutionContext.empty()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONVERSION_FAILED);
    assertThat(failure.stage()).isEqualTo(BundleStage.CONVERT);
    assertThat(failure.getMessage()).contains("outside the job");
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(f -> assertThat(f.documentId()).isEqualTo("d1"));
    assertThat(Files.exists(foreign)).isTrue(); // the foreign file is never touched
    assertThat(work.toFile().list()).isEmpty();
    assertThat(published.toFile().list()).isEmpty();
  }

  @Test
  void aHandlerAllocatingUnboundedTempFilesIsCappedTyped() {
    // F-7 (fixed): per-document temp-file allocation through the handler context is capped, so
    // a runaway handler cannot exhaust the disk with allocations.
    BundlingExtension greedy = new BundlingExtension() {
      @Override
      public String name() {
        return "greedy";
      }

      @Override
      public void configure(BundlingExtensionContext context) {
        context.addHandler("text/plain", (source, ctx) -> {
          Path last = null;
          for (int i = 0; i < 500; i++) {
            try {
              last = ctx.createTempFile(".tmp");
            } catch (IOException e) {
              throw new uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException(
                  e.getMessage(), e);
            }
          }
          return HandledDocument.of(last);
        });
      }
    };
    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .source("note", RenderTestSupport.Source.of(
            "hello".getBytes(StandardCharsets.US_ASCII), "text/plain", "note.txt"));
    BundleRenderer renderer = builder(resolver).extension(greedy).build();

    BundleGenerationException failure = catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Note", "note")),
            BundleExecutionContext.empty()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONVERSION_FAILED);
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(f -> assertThat(f.detail()).contains("allocation cap"));
    assertThat(work.toFile().list()).isEmpty();
  }

  @Test
  void aHandlerThrowingANakedRuntimeExceptionIsWrappedTypedWithoutLeakingItsMessage() {
    BundlingExtension throwing = new BundlingExtension() {
      @Override
      public String name() {
        return "throwing";
      }

      @Override
      public void configure(BundlingExtensionContext context) {
        context.addHandler("text/plain", (source, ctx) -> {
          throw new IllegalStateException("secret internal detail with a token: abc123");
        });
      }
    };
    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .source("note", RenderTestSupport.Source.of(
            "hello".getBytes(StandardCharsets.US_ASCII), "text/plain", "note.txt"));
    BundleRenderer renderer = builder(resolver).extension(throwing).build();

    BundleGenerationException failure = catchThrowableOfType(
        BundleGenerationException.class,
        () -> renderer.render(
            RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Note", "note")),
            BundleExecutionContext.empty()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_CONVERSION_FAILED);
    // The naked exception's message (which may carry anything) is not copied into the typed
    // failure; only the exception class name is.
    assertThat(failure.getMessage()).doesNotContain("abc123");
    assertThat(failure.documentFailures()).singleElement()
        .satisfies(f -> assertThat(f.detail()).contains("IllegalStateException"));
  }

  // ---------------------------------------------------------------------------------------
  // Resolution edges (attack area 6)
  // ---------------------------------------------------------------------------------------

  @Test
  void aResolverReturningTheSameResolvedInstanceForTwoReferencesStillRendersBoth() {
    byte[] pdf = fixture("one-page.pdf");
    ResolvedDocument shared = inMemoryDocument(pdf);
    DocumentResolver sharing = new DocumentResolver() {
      @Override
      public String provider() {
        return RenderTestSupport.PROVIDER;
      }

      @Override
      public ResolvedDocuments resolveAll(
          List<DocumentReference> references, BundleExecutionContext context) {
        Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
        for (DocumentReference reference : references) {
          resolved.put(reference, shared); // one instance, several references
        }
        return new ResolvedDocuments(resolved, Map.of());
      }
    };
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(sharing)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work)
        .build();
    BundleRequest request = BundleRequest.builder()
        .externalId(UUID.randomUUID())
        .title("Shared instance bundle")
        .fileName("shared.pdf")
        .root(BundleSection.builder("Case file")
            .document(RenderTestSupport.doc("d1", "One", "a"))
            .document(RenderTestSupport.doc("d2", "Two", "b"))
            .build())
        .build();

    BundleResult result = renderer.render(request, BundleExecutionContext.empty());

    assertThat(result.documents()).hasSize(2);
  }

  @Test
  void aResolverReturningEntriesItWasNeverAskedForIsHarmless() {
    byte[] pdf = fixture("one-page.pdf");
    DocumentResolver chatty = new DocumentResolver() {
      @Override
      public String provider() {
        return RenderTestSupport.PROVIDER;
      }

      @Override
      public ResolvedDocuments resolveAll(
          List<DocumentReference> references, BundleExecutionContext context) {
        Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
        for (DocumentReference reference : references) {
          resolved.put(reference, inMemoryDocument(pdf));
        }
        // An entry for a reference that is not in the request.
        resolved.put(new DocumentReference(RenderTestSupport.PROVIDER, "uninvited"),
            inMemoryDocument(pdf));
        return new ResolvedDocuments(resolved, Map.of());
      }
    };
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(chatty)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work)
        .build();

    BundleResult result = renderer.render(
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "One", "a")),
        BundleExecutionContext.empty());

    assertThat(result.documents()).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------

  private uk.gov.hmcts.ccd.sdk.bundling.api.BundleRendererBuilder builder(
      DocumentResolver resolver) {
    return BundleRenderer.builder()
        .resolver(resolver)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work);
  }

  private static BundleLimits shortDeadline(Duration maxElapsed) {
    BundleLimits defaults = BundleLimits.defaults();
    return new BundleLimits(
        defaults.maxDocumentCount(),
        defaults.maxSourceBytesPerDocument(),
        defaults.maxOfficeSourceBytesPerDocument(),
        defaults.maxOutputBytes(),
        defaults.maxTotalPages(),
        maxElapsed);
  }

  private static String docx() {
    return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  }

  private static byte[] zipHead() {
    byte[] head = new byte[1024];
    head[0] = 'P';
    head[1] = 'K';
    head[2] = 3;
    head[3] = 4;
    return head;
  }

  private static byte[] ole2Head() {
    byte[] head = new byte[1024];
    byte[] signature = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    System.arraycopy(signature, 0, head, 0, signature.length);
    return head;
  }

  private static byte[] pdfHead(int offset) {
    byte[] head = new byte[1024];
    Arrays.fill(head, 0, offset, (byte) ' ');
    byte[] signature = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(signature, 0, head, offset, signature.length);
    return head;
  }

  private static byte[] pngHead() {
    byte[] head = new byte[1024];
    byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(signature, 0, head, 0, signature.length);
    return head;
  }

  private static byte[] jpegHead() {
    byte[] head = new byte[1024];
    head[0] = (byte) 0xFF;
    head[1] = (byte) 0xD8;
    head[2] = (byte) 0xFF;
    return head;
  }

  private static byte[] mp3Head() {
    byte[] head = new byte[1024];
    head[0] = 'I';
    head[1] = 'D';
    head[2] = '3';
    return head;
  }

  /** A real, decodable PNG whose first kilobyte contains {@code text} in a tEXt chunk. */
  private static byte[] pngWithTextChunk(String text) throws IOException {
    BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    byte[] png = out.toByteArray();
    // Signature (8) + IHDR (4 len + 4 type + 13 data + 4 crc) = insert at offset 33.
    ByteArrayOutputStream chunkData = new ByteArrayOutputStream();
    chunkData.writeBytes("Comment".getBytes(StandardCharsets.US_ASCII));
    chunkData.write(0);
    chunkData.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    byte[] data = chunkData.toByteArray();
    CRC32 crc = new CRC32();
    crc.update("tEXt".getBytes(StandardCharsets.US_ASCII));
    crc.update(data);
    ByteArrayOutputStream assembled = new ByteArrayOutputStream();
    assembled.write(png, 0, 33);
    assembled.writeBytes(intBytes(data.length));
    assembled.writeBytes("tEXt".getBytes(StandardCharsets.US_ASCII));
    assembled.writeBytes(data);
    assembled.writeBytes(intBytes((int) crc.getValue()));
    assembled.write(png, 33, png.length - 33);
    return assembled.toByteArray();
  }

  private static byte[] intBytes(int value) {
    return new byte[] {
        (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
  }

  private static ResolvedDocument inMemoryDocument(byte[] content) {
    return new ResolvedDocument() {
      @Override
      public InputStream content() {
        return new java.io.ByteArrayInputStream(content);
      }

      @Override
      public String mediaType() {
        return "application/pdf";
      }

      @Override
      public String fileName() {
        return "doc.pdf";
      }

      @Override
      public OptionalLong contentLength() {
        return OptionalLong.of(content.length);
      }

      @Override
      public Optional<String> checksum() {
        return Optional.empty();
      }

      @Override
      public void close() {
      }
    };
  }

  /** A resolved document whose stream sleeps once before serving its bytes. */
  private static ResolvedDocument slowDocument(byte[] content, long sleepMillis) {
    return new ResolvedDocument() {
      @Override
      public InputStream content() {
        return new InputStream() {
          private final InputStream delegate = new java.io.ByteArrayInputStream(content);
          private boolean slept;

          private void sleepOnce() {
            if (!slept) {
              slept = true;
              try {
                Thread.sleep(sleepMillis);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          }

          @Override
          public int read() throws IOException {
            sleepOnce();
            return delegate.read();
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            sleepOnce();
            return delegate.read(b, off, len);
          }
        };
      }

      @Override
      public String mediaType() {
        return "application/pdf";
      }

      @Override
      public String fileName() {
        return "slow.pdf";
      }

      @Override
      public OptionalLong contentLength() {
        return OptionalLong.of(content.length);
      }

      @Override
      public Optional<String> checksum() {
        return Optional.empty();
      }

      @Override
      public void close() {
      }
    };
  }
}
