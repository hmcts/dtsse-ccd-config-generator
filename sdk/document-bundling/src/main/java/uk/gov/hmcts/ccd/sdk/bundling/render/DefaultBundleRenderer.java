package uk.gov.hmcts.ccd.sdk.bundling.render;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import uk.gov.hmcts.ccd.sdk.bundling.api.BuiltInMediaTypes;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStorageException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerRegistry;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.StoredBundle;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyResult;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;

/**
 * The rendering pipeline: the nine design steps behind {@code BundleRenderer.builder()}.
 *
 * <p><b>Concurrency.</b> A fair permit caps concurrent renders at the builder's
 * {@code maxConcurrentRenders}; excess renders <em>block</em> on the caller's thread until a
 * permit frees or the hard deadline elapses — they are not rejected, so a durable worker can
 * submit its claimed batch and let the permit do the queueing. The whole render, permit wait
 * included, is bounded by {@code BundleLimits.maxElapsed}: the render runs on the caller's
 * thread and the deadline is checked between stages, inside every per-document loop, and
 * between copy chunks while spooling, so cancellation is cooperative — a breach surfaces at the
 * next checkpoint as a {@link BundleRenderTimeoutException} ({@code TIMED_OUT}) carrying the
 * per-stage timings gathered so far, never mid-write, and the job's temporary directory is
 * still cleaned. Cooperative also means a single blocking call (one slow {@code read} on a
 * resolver stream, one handler invocation, one destination upload) can overshoot the deadline
 * by its own duration before the next checkpoint runs; the budget bounds the pipeline's loops,
 * not the underlying I/O primitives.
 *
 * <p><b>Observability.</b> Stage transitions log at INFO with document/page/byte counts under
 * MDC keys {@code externalId}, {@code stage}, and {@code documentId}; permitted warnings log at
 * WARN with their codes; a failure is logged exactly once, here, at the point of final failure,
 * with its full typed context — inner stages throw without logging. Optional Micrometer metrics
 * are published as documented on {@link RenderMetrics}. Tokens, document content, and raw
 * downstream error bodies never reach the logs.
 */
public final class DefaultBundleRenderer implements BundleRenderer {

  /** Warning code: a declared media type disagreed with detection; the detected type was used. */
  public static final String WARNING_MEDIA_TYPE_MISMATCH = "MEDIA_TYPE_MISMATCH";

  /** Warning code: a converted document yielded no extractable text (a report fact). */
  public static final String WARNING_NO_EXTRACTABLE_TEXT = "NO_EXTRACTABLE_TEXT";

  private static final Logger log = LoggerFactory.getLogger(DefaultBundleRenderer.class);
  private static final String MDC_EXTERNAL_ID = "externalId";
  private static final String MDC_STAGE = "stage";
  private static final String MDC_DOCUMENT_ID = "documentId";

  private final Map<String, DocumentResolver> resolvers;
  private final BundleDestination destination;
  private final DocmosisRenderService docmosis;
  private final HandlerRegistry registry;
  private final BundleLimits limits;
  private final int maxConcurrentRenders;
  private final Semaphore permits;
  private final RenderMetrics metrics;
  private final Path tempBase;
  private final PdfBundleAssembler assembler = new PdfBundleAssembler();

  /**
   * Creates the renderer; called by {@code BundleRenderer.builder()}, which validates the
   * configuration.
   *
   * @param resolvers the registered resolvers by provider
   * @param destination the destination that publishes the finished bundle
   * @param docmosis the Docmosis render service, or null when not configured
   * @param registry the effective handler registry
   * @param limits the limits to enforce
   * @param maxConcurrentRenders the maximum concurrent renders
   * @param meterRegistry the Micrometer registry, or null for no metrics
   * @param tempBase the base directory for job temp directories, or null for the JVM default
   */
  public DefaultBundleRenderer(
      Map<String, DocumentResolver> resolvers,
      BundleDestination destination,
      DocmosisRenderService docmosis,
      HandlerRegistry registry,
      BundleLimits limits,
      int maxConcurrentRenders,
      MeterRegistry meterRegistry,
      Path tempBase) {
    this.resolvers = Map.copyOf(resolvers);
    this.destination = destination;
    this.docmosis = docmosis;
    this.registry = registry;
    this.limits = limits;
    this.maxConcurrentRenders = maxConcurrentRenders;
    this.permits = new Semaphore(maxConcurrentRenders, true);
    this.metrics = new RenderMetrics(meterRegistry);
    this.tempBase = tempBase;
  }

  @Override
  public Set<String> handledMediaTypes() {
    return registry.handledMediaTypes();
  }

  @Override
  public BundleLimits limits() {
    return limits;
  }

  @Override
  public BundleResult render(BundleRequest request, BundleExecutionContext context) {
    if (request == null) {
      throw new IllegalArgumentException("request must be provided");
    }
    if (context == null) {
      throw new IllegalArgumentException("context must be provided");
    }
    String previousExternalId = MDC.get(MDC_EXTERNAL_ID);
    String previousStage = MDC.get(MDC_STAGE);
    String previousDocumentId = MDC.get(MDC_DOCUMENT_ID);
    MDC.put(MDC_EXTERNAL_ID, request.externalId().toString());
    try {
      long startNanos = System.nanoTime();
      acquirePermit();
      try {
        return new Render(request, context, startNanos).execute();
      } finally {
        permits.release();
      }
    } catch (BundleGenerationException e) {
      metrics.failure(e.code().name());
      log.error("Bundle generation failed. {}", e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      metrics.failure("UNEXPECTED");
      log.error("Bundle generation failed unexpectedly: {}", e.toString());
      throw e;
    } finally {
      restoreMdc(MDC_EXTERNAL_ID, previousExternalId);
      restoreMdc(MDC_STAGE, previousStage);
      restoreMdc(MDC_DOCUMENT_ID, previousDocumentId);
    }
  }

  private static void restoreMdc(String key, String previous) {
    if (previous == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, previous);
    }
  }

  private void acquirePermit() {
    boolean acquired;
    try {
      acquired = permits.tryAcquire(limits.maxElapsed().toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BundleGenerationException(BundleErrorCode.TIMED_OUT, BundleStage.VALIDATE,
          "The render was interrupted while waiting for a render permit.",
          "Resubmit the bundle.", List.of(), e);
    }
    if (!acquired) {
      throw new BundleRenderTimeoutException(BundleStage.VALIDATE,
          "The hard end-to-end timeout of " + limits.maxElapsed() + " elapsed while waiting for "
              + "a render permit; this renderer allows " + maxConcurrentRenders
              + " concurrent render(s).",
          "Reduce concurrent submissions or raise maxConcurrentRenders with evidence.",
          Map.of());
    }
  }

  /** One converted document: its request metadata, produced PDF, routed type, and checksum. */
  private record Converted(BundleDocument document, Path pdf, String mediaType, String sha256) {
  }

  private final class Render {

    private final BundleRequest request;
    private final BundleExecutionContext context;
    private final long startNanos;
    private final EnumMap<BundleStage, Duration> timings = new EnumMap<>(BundleStage.class);
    private final List<BundleWarning> warnings = new ArrayList<>();
    private BundleStage currentStage = BundleStage.VALIDATE;
    private long currentStageStartNanos;
    private Path jobDirectory;

    private Render(BundleRequest request, BundleExecutionContext context, long startNanos) {
      this.request = request;
      this.context = context;
      this.startNanos = startNanos;
    }

    private BundleResult execute() {
      try {
        jobDirectory = createJobDirectory();
        timedStage(BundleStage.VALIDATE, () -> {
          RequestValidation.validate(request, registry, limits);
          return null;
        });
        log.info("Validated bundle request: {} documents", request.allDocuments().size());
        checkDeadline();

        Map<DocumentReference, Resolution.Spooled> spooled = timedStage(BundleStage.RESOLVE,
            () -> Resolution.resolveAndSpool(
                request.allDocuments(), resolvers, context, jobDirectory, limits,
                this::checkDeadline));
        log.info("Resolved and spooled {} unique reference(s), {} bytes",
            spooled.size(), spooled.values().stream().mapToLong(Resolution.Spooled::size).sum());
        checkDeadline();

        Map<String, Converted> converted =
            timedStage(BundleStage.CONVERT, () -> convertAll(spooled));
        log.info("Converted {} document(s) to PDF", converted.size());
        checkDeadline();

        int sourcePages = timedStage(BundleStage.INSPECT, () -> inspectAll(converted));
        log.info("Inspected {} document(s): {} source pages", converted.size(), sourcePages);
        checkDeadline();

        AssemblyOutcome assembly = timedStage(BundleStage.ASSEMBLE, () -> assemble(converted));
        log.info("Assembled bundle: {} pages", assembly.result().totalPages());
        checkDeadline();

        Published published = timedStage(BundleStage.STORE, () -> store(assembly));
        BundleResult result = buildResult(assembly, converted, published);
        log.info("Published bundle '{}': {} pages, {} warning(s), outcome {}",
            request.fileName(), result.pageCount(), result.warnings().size(), result.outcome());
        return result;
      } finally {
        JobDirectory.deleteRecursively(jobDirectory);
      }
    }

    private Path createJobDirectory() {
      try {
        return JobDirectory.create(tempBase, request.externalId());
      } catch (IOException e) {
        throw new UncheckedIOException(
            "Could not create the job's temporary directory under "
                + (tempBase != null ? tempBase : Path.of(System.getProperty("java.io.tmpdir"))),
            e);
      }
    }

    private Map<String, Converted> convertAll(
        Map<DocumentReference, Resolution.Spooled> spooled) {
      Map<String, Converted> outcome = new LinkedHashMap<>();
      for (BundleDocument document : request.allDocuments()) {
        checkDeadline();
        MDC.put(MDC_DOCUMENT_ID, document.id());
        try {
          long documentStart = System.nanoTime();
          Converted converted = convertOne(document, spooled);
          outcome.put(document.id(), converted);
          log.info("Converted document '{}' as {} in {} ms", document.id(),
              converted.mediaType(),
              Duration.ofNanos(System.nanoTime() - documentStart).toMillis());
        } finally {
          MDC.remove(MDC_DOCUMENT_ID);
        }
      }
      return outcome;
    }

    private Converted convertOne(
        BundleDocument document, Map<DocumentReference, Resolution.Spooled> spooled) {
      ResolvedDocument source;
      String effectiveType;
      String sourceSha = null;
      if (document.media().isPresent()) {
        effectiveType = MediaTypes.normalise(document.media().get().mediaType().orElse(""));
        source = new SyntheticMediaSource(effectiveType, document.id());
      } else {
        Resolution.Spooled spool = spooled.get(document.reference());
        effectiveType = routeMediaType(document, spool);
        source = new SpooledSource(spool.file(), effectiveType, spool.fileName(), spool.size(),
            spool.sha256(), spool.providerChecksum());
        sourceSha = spool.sha256();
      }

      if (effectiveType.isBlank()) {
        throw new BundleGenerationException(
            BundleErrorCode.MEDIA_TYPE_UNSUPPORTED, BundleStage.CONVERT,
            "Document '" + document.id() + "' has no usable media type: the resolver declared "
                + "none (missing or blank) and its content matched no known signature. "
                + "Registered types: " + registry.handledMediaTypes() + ".",
            "Have the resolver declare the source's media type, or correct the source content.",
            List.of(new DocumentFailure(document.id(), document.reference(),
                BundleErrorCode.MEDIA_TYPE_UNSUPPORTED,
                "The declared media type is missing or blank and content detection found no "
                    + "signature")));
      }
      DocumentHandler handler = registry.handlerFor(effectiveType)
          .orElseThrow(() -> unsupportedMediaType(document, effectiveType));

      HandledDocument handled;
      try {
        handled = handler.handle(source,
            new DefaultHandlerContext(jobDirectory, document,
                Optional.ofNullable(docmosis), limits));
      } catch (DocumentHandlingException | RuntimeException e) {
        throw new BundleGenerationException(
            BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT,
            "Document '" + document.id() + "' could not be converted to PDF.",
            "Check the source document is valid, then resubmit the bundle.",
            List.of(new DocumentFailure(document.id(), document.reference(),
                BundleErrorCode.DOCUMENT_CONVERSION_FAILED,
                e instanceof DocumentHandlingException
                    ? e.getMessage()
                    : "The handler threw " + e.getClass().getSimpleName())),
            e);
      }
      Path producedPdf = requireInsideJobDirectory(document, handler, handled.pdfFile());
      handled.warnings().forEach(this::addWarning);
      String sha = sourceSha != null ? sourceSha : sha256Of(producedPdf);
      return new Converted(document, producedPdf, effectiveType, sha);
    }

    /**
     * Containment for handler output: the pipeline stitches only files that live under the
     * job's temporary directory, where they were written through the handler context and where
     * job cleanup owns them. Anything else — a path from another job, an arbitrary readable
     * PDF the handler names — is rejected typed rather than silently read into a court bundle.
     *
     * @param document the document being converted
     * @param handler the handler that produced the file
     * @param pdfFile the handler's claimed output
     * @return the resolved real path of the contained file
     */
    private Path requireInsideJobDirectory(
        BundleDocument document, DocumentHandler handler, Path pdfFile) {
      String handlerName = handler.getClass().getName();
      try {
        Path real = pdfFile.toRealPath();
        if (real.startsWith(jobDirectory.toRealPath())) {
          return real;
        }
      } catch (IOException e) {
        throw new BundleGenerationException(
            BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT,
            "Document '" + document.id() + "' could not be converted: handler " + handlerName
                + " returned a PDF path that does not resolve to a readable file.",
            "Fix the handler to return the file it wrote through "
                + "HandlerContext.createTempFile.",
            List.of(new DocumentFailure(document.id(), document.reference(),
                BundleErrorCode.DOCUMENT_CONVERSION_FAILED,
                "The handler's output path could not be resolved")), e);
      }
      throw new BundleGenerationException(
          BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT,
          "Document '" + document.id() + "' could not be converted: handler " + handlerName
              + " returned a PDF outside the job's temporary directory.",
          "Handlers must allocate their output through HandlerContext.createTempFile so the "
              + "job owns and cleans it.",
          List.of(new DocumentFailure(document.id(), document.reference(),
              BundleErrorCode.DOCUMENT_CONVERSION_FAILED,
              "Handler " + handlerName + " returned a file outside the job directory")));
    }

    private String routeMediaType(BundleDocument document, Resolution.Spooled spool) {
      byte[] head = new byte[MediaTypes.DETECTION_PREFIX_BYTES];
      int length;
      try (InputStream in = Files.newInputStream(spool.file())) {
        length = in.readNBytes(head, 0, head.length);
      } catch (IOException e) {
        throw new UncheckedIOException("Could not re-read a spooled source file", e);
      }
      MediaTypes.Routing routing = MediaTypes.route(spool.declaredMediaType(), head, length);
      String effectiveType = switch (routing) {
        case MediaTypes.Routing.Route route -> route.mediaType();
        case MediaTypes.Routing.RouteWithMismatch mismatch -> {
          addWarning(BundleWarning.forDocument(WARNING_MEDIA_TYPE_MISMATCH,
              "Document '" + document.id() + "' declares media type '" + mismatch.declared()
                  + "' but its content was detected as '" + mismatch.detected() + "'; the "
                  + "detected type was used for conversion.",
              document.id()));
          yield mismatch.mediaType();
        }
        case MediaTypes.Routing.Irreconcilable mismatch ->
            throw contentInvalid(document, "declares media type '" + mismatch.declared()
                + "' but its content was detected as " + mismatch.detectedDescription());
      };
      if (BuiltInMediaTypes.MEDIA.contains(effectiveType)) {
        throw contentInvalid(document, "resolved to recorded media content ('" + effectiveType
            + "'); audio and video documents are metadata-only and must carry a "
            + "MediaPlaceholder instead of content");
      }
      return effectiveType;
    }

    /**
     * The typed failure for a media type with no registered handler. Office types without a
     * configured Docmosis get their dedicated code: the design promises an error that says
     * exactly that and names the properties to set.
     *
     * @param document the affected document
     * @param effectiveType the routed media type with no handler
     * @return the typed failure to throw
     */
    private BundleGenerationException unsupportedMediaType(
        BundleDocument document, String effectiveType) {
      if (docmosis == null && BuiltInMediaTypes.OFFICE.contains(effectiveType)) {
        return new BundleGenerationException(
            BundleErrorCode.DOCMOSIS_NOT_CONFIGURED, BundleStage.CONVERT,
            "Document '" + document.id() + "' is an office-format source ('" + effectiveType
                + "') but the Docmosis render service is not configured, so there is no office "
                + "conversion handler.",
            "Configure Docmosis (ccd.bundling.docmosis.convert-endpoint, "
                + "ccd.bundling.docmosis.render-endpoint, ccd.bundling.docmosis.access-key — or "
                + "call docmosis(...) on the renderer builder), or register a replacement "
                + "handler for the media type through a BundlingExtension.",
            List.of(new DocumentFailure(document.id(), document.reference(),
                BundleErrorCode.DOCMOSIS_NOT_CONFIGURED,
                "No Docmosis render service is configured for office conversion of '"
                    + effectiveType + "'")));
      }
      return new BundleGenerationException(
          BundleErrorCode.MEDIA_TYPE_UNSUPPORTED, BundleStage.CONVERT,
          "The media type '" + effectiveType + "' of document '" + document.id() + "' has "
              + "no registered handler. Registered types: "
              + registry.handledMediaTypes() + ".",
          "Register a handler for the media type through a BundlingExtension, or correct "
              + "the source.",
          List.of(new DocumentFailure(document.id(), document.reference(),
              BundleErrorCode.MEDIA_TYPE_UNSUPPORTED,
              "No handler is registered for '" + effectiveType + "'")));
    }

    private BundleGenerationException contentInvalid(BundleDocument document, String detail) {
      return new BundleGenerationException(
          BundleErrorCode.DOCUMENT_CONTENT_INVALID, BundleStage.CONVERT,
          "Document '" + document.id() + "' " + detail + ".",
          "Check the document was uploaded with the right content and declared type, then "
              + "resubmit the bundle.",
          List.of(new DocumentFailure(document.id(), document.reference(),
              BundleErrorCode.DOCUMENT_CONTENT_INVALID, detail)));
    }

    private int inspectAll(Map<String, Converted> converted) {
      int totalSourcePages = 0;
      for (Converted document : converted.values()) {
        checkDeadline();
        MDC.put(MDC_DOCUMENT_ID, document.document().id());
        try {
          PdfInspection.Facts facts;
          try {
            facts = PdfInspection.inspect(document.pdf(), jobDirectory);
          } catch (PdfInspection.InspectionException e) {
            throw new BundleGenerationException(
                BundleErrorCode.DOCUMENT_INSPECTION_FAILED, BundleStage.INSPECT,
                "Document '" + document.document().id() + "' failed inspection after "
                    + "conversion.",
                "Check the source document is a readable, unencrypted document, then resubmit "
                    + "the bundle.",
                List.of(new DocumentFailure(document.document().id(),
                    document.document().reference(),
                    BundleErrorCode.DOCUMENT_INSPECTION_FAILED, e.getMessage())),
                e);
          }
          if (!facts.hasExtractableText()) {
            addWarning(BundleWarning.forDocument(WARNING_NO_EXTRACTABLE_TEXT,
                "Document '" + document.document().id() + "' has no extractable text; if it is "
                    + "scanned evidence, apply OCR before bundling if searchability is "
                    + "required.",
                document.document().id()));
          }
          totalSourcePages += facts.pageCount();
          if (totalSourcePages > limits.maxTotalPages()) {
            throw new BundleGenerationException(
                BundleErrorCode.LIMIT_EXCEEDED, BundleStage.INSPECT,
                "The bundle's source documents accumulate more than the configured maximum of "
                    + limits.maxTotalPages() + " pages (reached " + totalSourcePages
                    + " before assembly).",
                "Split the bundle or raise BundleLimits.maxTotalPages with evidence.",
                List.of());
          }
        } finally {
          MDC.remove(MDC_DOCUMENT_ID);
        }
      }
      return totalSourcePages;
    }

    private AssemblyOutcome assemble(Map<String, Converted> converted) {
      Map<String, Path> handledPdfs = new LinkedHashMap<>();
      converted.forEach((id, document) -> handledPdfs.put(id, document.pdf()));
      AssemblyMapping.Mapped mapped = AssemblyMapping.map(request, handledPdfs);
      AssemblyResult result;
      try {
        result = assembler.assemble(mapped.request(), jobDirectory);
      } catch (IOException | RuntimeException e) {
        throw new BundleGenerationException(
            BundleErrorCode.ASSEMBLY_FAILED, BundleStage.ASSEMBLE,
            "PDF assembly failed: " + e.getMessage(),
            "Check the source documents merge cleanly, then resubmit the bundle.",
            List.of(), e);
      }
      result.warnings().forEach(this::addWarning);
      return new AssemblyOutcome(result, mapped.origins());
    }

    private Published store(AssemblyOutcome assembly) {
      AssemblyResult result = assembly.result();
      // Internal-consistency guard BEFORE publication: if the pipeline cannot attribute every
      // assembled item back to its request document, the report would be wrong — refuse to
      // publish rather than publish and then fail building the result.
      if (result.items().size() != assembly.origins().size()) {
        throw new BundleGenerationException(
            BundleErrorCode.ASSEMBLY_FAILED, BundleStage.STORE,
            "Internal error: the assembler placed " + result.items().size()
                + " items but the pipeline mapped " + assembly.origins().size()
                + "; nothing was published.",
            "Report this as a document-bundling defect.", List.of());
      }
      long size;
      try {
        size = Files.size(result.outputPdf());
      } catch (IOException e) {
        throw new UncheckedIOException("Could not read the assembled bundle's size", e);
      }
      if (size > limits.maxOutputBytes()) {
        throw new BundleGenerationException(
            BundleErrorCode.LIMIT_EXCEEDED, BundleStage.STORE,
            "The finished bundle is " + size + " bytes, which exceeds the configured maximum "
                + "of " + limits.maxOutputBytes() + " bytes; nothing was published.",
            "Split the bundle or raise BundleLimits.maxOutputBytes with evidence.",
            List.of());
      }
      if (result.totalPages() > limits.maxTotalPages()) {
        throw new BundleGenerationException(
            BundleErrorCode.LIMIT_EXCEEDED, BundleStage.STORE,
            "The finished bundle has " + result.totalPages() + " pages, which exceeds the "
                + "configured maximum of " + limits.maxTotalPages()
                + " pages; nothing was published.",
            "Split the bundle or raise BundleLimits.maxTotalPages with evidence.",
            List.of());
      }

      FileArtifact artifact = new FileArtifact(result.outputPdf(), request.fileName(), size,
          sha256Of(result.outputPdf()), result.totalPages());
      StoredBundle stored;
      try {
        stored = destination.store(artifact, context);
      } catch (RuntimeException e) {
        if (e instanceof BundleStorageException storageException && storageException.isPermanent()) {
          throw new BundleGenerationException(
              BundleErrorCode.STORAGE_REJECTED, BundleStage.STORE,
              "The destination rejected the finished bundle permanently ("
                  + e.getClass().getSimpleName() + "): " + e.getMessage(),
              "Fix the rejection's cause — destination permissions, upload coordinates, or the "
                  + "execution context — before resubmitting; retrying unchanged cannot succeed.",
              List.of(), e);
        }
        throw new BundleGenerationException(
            BundleErrorCode.STORAGE_FAILED, BundleStage.STORE,
            "The destination failed while storing the finished bundle ("
                + e.getClass().getSimpleName() + "). The publication state is unknown: the "
                + "destination may or may not have persisted the artifact before failing, and "
                + "no stored reference was returned.",
            "Check the destination's availability and whether the artifact was stored, then "
                + "resubmit the bundle.",
            List.of(), e);
      }
      return new Published(stored, size, result.totalPages());
    }

    private BundleResult buildResult(
        AssemblyOutcome assembly, Map<String, Converted> converted, Published published) {
      List<DocumentResult> documents = documentResults(assembly, converted);
      metrics.documents(documents.size());
      metrics.pages(published.totalPages());
      metrics.bytes(published.size());
      return new BundleResult(
          warnings.isEmpty() ? BundleOutcome.COMPLETED : BundleOutcome.COMPLETED_WITH_WARNINGS,
          CcdBundles.build(request, published.stored(), LocalDateTime.now()),
          published.stored(),
          published.totalPages(),
          List.copyOf(warnings),
          documents,
          Map.copyOf(timings));
    }

    private List<DocumentResult> documentResults(
        AssemblyOutcome assembly, Map<String, Converted> converted) {
      List<AssemblyMapping.Origin> origins = assembly.origins();
      List<DocumentResult> documents = new ArrayList<>();
      for (int i = 0; i < origins.size(); i++) {
        BundleDocument origin = origins.get(i).document();
        if (origin == null) {
          continue;
        }
        Converted convertedDocument = converted.get(origin.id());
        documents.add(new DocumentResult(
            origin.id(),
            origin.reference(),
            convertedDocument.mediaType(),
            convertedDocument.sha256(),
            assembly.result().items().get(i).pageCount(),
            assembly.result().items().get(i).startPage()));
      }
      return documents;
    }

    private void addWarning(BundleWarning warning) {
      warnings.add(warning);
      metrics.warning(warning.code());
      log.warn("{}: {}", warning.code(), warning.message());
    }

    private <T> T timedStage(BundleStage stage, Supplier<T> body) {
      currentStage = stage;
      currentStageStartNanos = System.nanoTime();
      MDC.put(MDC_STAGE, stage.name());
      long stageStart = currentStageStartNanos;
      try {
        return body.get();
      } finally {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - stageStart);
        timings.put(stage, elapsed);
        metrics.stage(stage, elapsed);
      }
    }

    private void checkDeadline() {
      Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
      if (elapsed.compareTo(limits.maxElapsed()) > 0) {
        Map<BundleStage, Duration> timingsSoFar = timingsIncludingInFlightStage();
        throw new BundleRenderTimeoutException(
            currentStage,
            "The hard end-to-end timeout of " + limits.maxElapsed() + " elapsed (at " + elapsed
                + ", during stage " + currentStage + "). Stage timings so far: "
                + describeTimings(timingsSoFar) + ". Nothing was published.",
            "Reduce the bundle size or raise BundleLimits.maxElapsed with evidence.",
            timingsSoFar);
      }
    }

    /**
     * The recorded stage timings plus the in-flight stage's elapsed-so-far entry, which its
     * {@code timedStage} finally has not written yet when a deadline fires mid-stage.
     *
     * @return the per-stage timings up to now
     */
    private Map<BundleStage, Duration> timingsIncludingInFlightStage() {
      EnumMap<BundleStage, Duration> soFar = new EnumMap<>(timings);
      if (currentStageStartNanos != 0 && !soFar.containsKey(currentStage)) {
        soFar.put(currentStage, Duration.ofNanos(System.nanoTime() - currentStageStartNanos));
      }
      return soFar;
    }

    private String describeTimings(Map<BundleStage, Duration> timingsSoFar) {
      StringBuilder text = new StringBuilder("{");
      timingsSoFar.forEach((stage, duration) -> {
        if (text.length() > 1) {
          text.append(", ");
        }
        text.append(stage).append('=').append(duration.toMillis()).append("ms");
      });
      return text.append('}').toString();
    }
  }

  private record AssemblyOutcome(AssemblyResult result, List<AssemblyMapping.Origin> origins) {
  }

  private record Published(StoredBundle stored, long size, int totalPages) {
  }

  private static String sha256Of(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
        in.transferTo(OutputStreamSink.INSTANCE);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JVM algorithm", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not checksum " + file.getFileName(), e);
    }
  }

  /** A discard sink for checksum streaming. */
  private static final class OutputStreamSink extends java.io.OutputStream {

    private static final OutputStreamSink INSTANCE = new OutputStreamSink();

    @Override
    public void write(int b) {
      // Discard: only the digest side-effect matters.
    }

    @Override
    public void write(byte[] b, int off, int len) {
      // Discard: only the digest side-effect matters.
    }
  }
}
