package uk.gov.hmcts.ccd.sdk.bundling.render;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import uk.gov.hmcts.ccd.sdk.bundling.api.BuiltInMediaTypes;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.CoverPage;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerRegistry;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.WatermarkPreset;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyResult;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfSource;

public final class DefaultBundleRenderer implements BundleRenderer {
  public static final String WARNING_MEDIA_TYPE_MISMATCH = "MEDIA_TYPE_MISMATCH";

  public static final String WARNING_NO_EXTRACTABLE_TEXT = "NO_EXTRACTABLE_TEXT";

  static final String RENDER_ENDPOINT_PROPERTY = "ccd.bundling.docmosis.render-endpoint";

  private static final Logger log = LoggerFactory.getLogger(DefaultBundleRenderer.class);
  private static final String MDC_EXTERNAL_ID = "externalId";
  private static final String MDC_STAGE = "stage";
  private static final String MDC_DOCUMENT_ID = "documentId";

  private final Map<String, DocumentResolver> resolvers;
  private final DocmosisRenderService docmosis;
  private final HandlerRegistry registry;
  private final BundleLimits limits;
  private final Semaphore permits;
  private final RenderMetrics metrics;
  private final Path tempBase;
  private final Map<String, Path> watermarkImages;
  private final PdfBundleAssembler assembler = new PdfBundleAssembler();

  public DefaultBundleRenderer(
      Map<String, DocumentResolver> resolvers,
      DocmosisRenderService docmosis,
      HandlerRegistry registry,
      BundleLimits limits,
      int maxConcurrentRenders,
      MeterRegistry meterRegistry,
      Path tempBase,
      Map<String, Path> watermarkImages) {
    this.resolvers = Map.copyOf(resolvers);
    this.watermarkImages = Map.copyOf(watermarkImages);
    this.docmosis = docmosis;
    this.registry = registry;
    this.limits = limits;
    this.permits = new Semaphore(maxConcurrentRenders, true);
    this.metrics = new RenderMetrics(meterRegistry);
    this.tempBase = tempBase;
  }

  @Override
  public Set<String> handledMediaTypes() {
    return registry.handledMediaTypes();
  }

  @Override
  public BundleResult render(BundleRequest request, BundleExecutionContext context) {
    if (request == null || context == null) {
      throw new IllegalArgumentException("request and context must be provided");
    }
    String previousExternalId = MDC.get(MDC_EXTERNAL_ID);
    String previousStage = MDC.get(MDC_STAGE);
    String previousDocumentId = MDC.get(MDC_DOCUMENT_ID);
    MDC.put(MDC_EXTERNAL_ID, request.externalId().toString());
    permits.acquireUninterruptibly();
    try {
      return new Render(request, context).execute();
    } catch (BundleGenerationException e) {
      metrics.failure(e.code().name());
      log.error("Bundle generation failed. {}", e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      metrics.failure("UNEXPECTED");
      log.error("Bundle generation failed unexpectedly: {}", e.toString());
      throw e;
    } finally {
      permits.release();
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

  private record Converted(BundleDocument document, Path pdf, String mediaType, String sha256,
      boolean generated) {
  }

  private record Conversion(Map<String, Converted> documents, Optional<Path> coverPage) {
  }

  private final class Render {
    private final BundleRequest request;
    private final BundleExecutionContext context;
    private final List<BundleWarning> warnings = new ArrayList<>();
    private final EnumMap<BundleStage, Duration> timings = new EnumMap<>(BundleStage.class);
    private Path jobDirectory;

    private Render(BundleRequest request, BundleExecutionContext context) {
      this.request = request;
      this.context = context;
    }

    private BundleResult execute() {
      boolean handedOver = false;
      try {
        jobDirectory = createJobDirectory();
        timedStage(BundleStage.VALIDATE, () -> {
          validate();
          return null;
        });
        log.info("Validated bundle request: {} documents", request.allDocuments().size());
        Map<DocumentReference, Resolution.Spooled> spooled = timedStage(BundleStage.RESOLVE,
            () -> Resolution.resolveAndSpool(
                request.allDocuments(), resolvers, context, jobDirectory, limits));
        log.info("Resolved and spooled {} unique reference(s), {} bytes", spooled.size(),
            spooled.values().stream().mapToLong(Resolution.Spooled::size).sum());
        Conversion conversion = timedStage(BundleStage.CONVERT,
            () -> new Conversion(convertAll(spooled), renderCoverPage()));
        Map<String, Converted> converted = conversion.documents();
        Optional<Path> coverPage = conversion.coverPage();
        log.info("Converted {} document(s) to PDF{}", converted.size(),
            coverPage.isPresent() ? " plus the cover page" : "");
        int sourcePages = timedStage(BundleStage.INSPECT, () -> inspectAll(converted));
        log.info("Inspected {} document(s): {} source pages", converted.size(), sourcePages);
        AssemblyOutcome assembly = timedStage(BundleStage.ASSEMBLE,
            () -> assemble(converted, coverPage));
        BundleResult result = buildResult(assembly, converted);
        metrics.rendered(result.documents().size(), result.pageCount(), assembly.size());
        log.info("Rendered bundle '{}': {} pages, {} warning(s), timings {}",
            request.fileName(), result.pageCount(), warnings.size(), describe(timings));
        handedOver = true;
        return result;
      } finally {
        if (!handedOver) {
          JobDirectory.deleteRecursively(jobDirectory);
        }
      }
    }

    private Path createJobDirectory() {
      try {
        return JobDirectory.create(tempBase, request.externalId());
      } catch (IOException e) {
        throw new UncheckedIOException("Could not create the job's temporary directory under "
            + (tempBase != null ? tempBase : Path.of(System.getProperty("java.io.tmpdir"))), e);
      }
    }

    private void validate() {
      int count = request.allDocuments().size();
      if (count > limits.maxDocumentCount()) {
        throw new BundleGenerationException(BundleErrorCode.LIMIT_EXCEEDED, BundleStage.VALIDATE,
            "The request contains " + count + " documents, which exceeds the configured maximum "
                + "of " + limits.maxDocumentCount() + ".",
            "Split the bundle or raise BundleLimits.maxDocumentCount with evidence.", List.of());
      }
      request.presentation().watermark().ifPresent(this::validateWatermark);
      request.coverPage().ifPresent(this::validateCoverPage);
      for (BundleDocument document : request.allDocuments()) {
        document.media().ifPresent(media -> {
          String type = MediaTypes.normalise(media.mediaType().orElse(""));
          if (BuiltInMediaTypes.PDF.equals(type) || BuiltInMediaTypes.IMAGES.contains(type)
              || BuiltInMediaTypes.OFFICE.contains(type)) {
            throw new BundleGenerationException(
                BundleErrorCode.REQUEST_INVALID, BundleStage.VALIDATE,
                "Document '" + document.id() + "' carries a media placeholder declaring '" + type
                    + "', a content type; placeholders are for audio and video that is never "
                    + "fetched.",
                "Supply the document as an ordinary reference, or use an audio/video media type.",
                List.of(new DocumentFailure(document.id(), document.reference(),
                    BundleErrorCode.REQUEST_INVALID, "Media placeholder declares content type '"
                        + type + "'")));
          }
          if (type.isBlank() || registry.handlerFor(type).isEmpty()) {
            throw new BundleGenerationException(
                BundleErrorCode.REQUEST_INVALID, BundleStage.VALIDATE,
                "Document '" + document.id() + "' carries a media placeholder whose media type '"
                    + type + "' is not a registered media type.",
                "Use a registered audio/video media type, or register a handler for it through "
                    + "a BundlingExtension.",
                List.of(new DocumentFailure(document.id(), document.reference(),
                    BundleErrorCode.REQUEST_INVALID, "Unregistered media placeholder type '"
                        + type + "'")));
          }
        });
      }
    }

    private void validateWatermark(WatermarkPreset preset) {
      if (!watermarkImages.containsKey(preset.imageName())) {
        throw new BundleGenerationException(
            BundleErrorCode.REQUEST_INVALID, BundleStage.VALIDATE,
            "The presentation's watermark refers to image '" + preset.imageName()
                + "', which is not registered on the renderer; registered images: "
                + watermarkImages.keySet() + ".",
            "Register the image with BundleRendererBuilder.watermarkImage(name, path) or "
                + "refer to a registered name.", List.of());
      }
    }

    private void validateCoverPage(CoverPage coverPage) {
      if (docmosis == null || !docmosis.rendersTemplates()) {
        throw new BundleGenerationException(
            BundleErrorCode.DOCMOSIS_NOT_CONFIGURED, BundleStage.VALIDATE,
            "The request has a cover page (template '" + coverPage.templateName() + "') but "
                + "the Docmosis render endpoint is not configured.",
            "Configure " + RENDER_ENDPOINT_PROPERTY + " and ccd.bundling.docmosis.access-key "
                + "(or call docmosis(...) on the renderer builder with a service that renders "
                + "templates), or remove the cover page.", List.of());
      }
    }

    private Optional<Path> renderCoverPage() {
      if (request.coverPage().isEmpty()) {
        return Optional.empty();
      }
      CoverPage coverPage = request.coverPage().get();
      try {
        Path rendered = docmosis.renderTemplate(coverPage.templateName(), coverPage.data());
        // Move the rendered PDF into the job directory so the job's cleanup owns it.
        Path owned = JobDirectory.createFile(jobDirectory, ".pdf");
        Files.move(rendered, owned, StandardCopyOption.REPLACE_EXISTING);
        log.info("Rendered cover page from template '{}'", coverPage.templateName());
        return Optional.of(owned);
      } catch (DocmosisRenderException | IOException | RuntimeException e) {
        throw new BundleGenerationException(
            BundleErrorCode.COVER_PAGE_FAILED, BundleStage.CONVERT,
            "The cover page template '" + coverPage.templateName() + "' could not be rendered: "
                + (e instanceof DocmosisRenderException ? e.getMessage()
                    : "the render threw " + e.getClass().getSimpleName()),
            "Check the template exists on the Docmosis instance and its data is complete, then "
                + "resubmit the bundle.", List.of(), e);
      }
    }

    private int inspectAll(Map<String, Converted> converted) {
      int totalSourcePages = 0;
      for (Converted document : converted.values()) {
        String documentId = document.document().id();
        MDC.put(MDC_DOCUMENT_ID, documentId);
        try {
          PdfInspection.Facts facts;
          try {
            facts = PdfInspection.inspect(document.pdf(), jobDirectory);
          } catch (PdfInspection.InspectionException e) {
            throw new BundleGenerationException(
                BundleErrorCode.DOCUMENT_INSPECTION_FAILED, BundleStage.INSPECT,
                "Document '" + documentId + "' failed inspection after conversion: "
                    + e.getMessage() + ".",
                "Check the source document is a readable, unencrypted document, then resubmit "
                    + "the bundle.",
                List.of(new DocumentFailure(documentId, document.document().reference(),
                    BundleErrorCode.DOCUMENT_INSPECTION_FAILED, e.getMessage())), e);
          }
          if (!facts.hasExtractableText()) {
            addWarning(BundleWarning.forDocument(WARNING_NO_EXTRACTABLE_TEXT,
                "Document '" + documentId + "' has no extractable text; if it is scanned "
                    + "evidence, apply OCR before bundling if searchability is required.",
                documentId));
          }
          totalSourcePages += facts.pageCount();
          if (totalSourcePages > limits.maxTotalPages()) {
            throw new BundleGenerationException(BundleErrorCode.LIMIT_EXCEEDED,
                BundleStage.INSPECT,
                "The source documents accumulate more than the configured maximum of "
                    + limits.maxTotalPages() + " pages (reached " + totalSourcePages
                    + " at document '" + documentId + "', before assembly).",
                "Split the bundle or raise BundleLimits.maxTotalPages with evidence.",
                List.of());
          }
        } finally {
          MDC.remove(MDC_DOCUMENT_ID);
        }
      }
      return totalSourcePages;
    }

    private Map<String, Converted> convertAll(
        Map<DocumentReference, Resolution.Spooled> spooled) {
      Map<String, Converted> outcome = new LinkedHashMap<>();
      for (BundleDocument document : request.allDocuments()) {
        MDC.put(MDC_DOCUMENT_ID, document.id());
        try {
          long start = System.nanoTime();
          outcome.put(document.id(), convertOne(document, spooled));
          log.info("Converted document '{}' in {} ms", document.id(),
              Duration.ofNanos(System.nanoTime() - start).toMillis());
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
                + "none and its content matched no known signature. Registered types: "
                + registry.handledMediaTypes() + ".",
            "Have the resolver declare the source's media type, or correct the source content.",
            List.of(new DocumentFailure(document.id(), document.reference(),
                BundleErrorCode.MEDIA_TYPE_UNSUPPORTED,
                "The declared media type is missing and content detection found no signature")));
      }
      DocumentHandler handler = registry.handlerFor(effectiveType)
          .orElseThrow(() -> unsupportedMediaType(document, effectiveType));
      HandledDocument handled;
      try {
        handled = handler.handle(source,
            new DefaultHandlerContext(jobDirectory, document, Optional.ofNullable(docmosis)));
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
      return new Converted(document, producedPdf, effectiveType, sha, handled.generated());
    }

    private Path requireInsideJobDirectory(
        BundleDocument document, DocumentHandler handler, Path pdfFile) {
      String handlerName = handler.getClass().getName();
      try {
        Path real = pdfFile.toRealPath();
        if (real.startsWith(jobDirectory.toRealPath())) {
          return real;
        }
      } catch (IOException e) {
        throw conversionFailed(document, "handler " + handlerName
            + " returned a PDF path that does not resolve to a readable file", e);
      }
      throw conversionFailed(document, "handler " + handlerName
          + " returned a PDF outside the job's temporary directory", null);
    }

    private BundleGenerationException conversionFailed(
        BundleDocument document, String detail, Throwable cause) {
      return new BundleGenerationException(
          BundleErrorCode.DOCUMENT_CONVERSION_FAILED, BundleStage.CONVERT,
          "Document '" + document.id() + "' could not be converted: " + detail + ".",
          "Handlers must allocate their output through HandlerContext.createTempFile so the "
              + "job owns and cleans it.",
          List.of(new DocumentFailure(document.id(), document.reference(),
              BundleErrorCode.DOCUMENT_CONVERSION_FAILED, detail)), cause);
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

    private BundleGenerationException unsupportedMediaType(
        BundleDocument document, String effectiveType) {
      if (docmosis == null && BuiltInMediaTypes.OFFICE.contains(effectiveType)) {
        return new BundleGenerationException(
            BundleErrorCode.DOCMOSIS_NOT_CONFIGURED, BundleStage.CONVERT,
            "Document '" + document.id() + "' is an office-format source ('" + effectiveType
                + "') but the Docmosis render service is not configured, so there is no office "
                + "conversion handler.",
            "Configure Docmosis (ccd.bundling.docmosis.convert-endpoint and "
                + "ccd.bundling.docmosis.access-key — or call docmosis(...) on the renderer "
                + "builder), or register a replacement handler for the media type through a "
                + "BundlingExtension.",
            List.of(new DocumentFailure(document.id(), document.reference(),
                BundleErrorCode.DOCMOSIS_NOT_CONFIGURED,
                "No Docmosis render service is configured for office conversion of '"
                    + effectiveType + "'")));
      }
      return new BundleGenerationException(
          BundleErrorCode.MEDIA_TYPE_UNSUPPORTED, BundleStage.CONVERT,
          "The media type '" + effectiveType + "' of document '" + document.id() + "' has "
              + "no registered handler. Registered types: " + registry.handledMediaTypes() + ".",
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

    private AssemblyOutcome assemble(Map<String, Converted> converted, Optional<Path> coverPage) {
      Map<String, PdfSource> handledPdfs = new LinkedHashMap<>();
      converted.forEach((id, document) ->
          handledPdfs.put(id, new PdfSource(document.pdf(), document.generated())));
      AssemblyMapping.Mapped mapped =
          AssemblyMapping.map(request, handledPdfs, coverPage, watermarkImages);
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
      if (result.items().size() != mapped.origins().size()) {
        throw new BundleGenerationException(
            BundleErrorCode.ASSEMBLY_FAILED, BundleStage.ASSEMBLE,
            "Internal error: the assembler placed " + result.items().size()
                + " items but the pipeline mapped " + mapped.origins().size() + ".",
            "Report this as a document-bundling defect.", List.of());
      }
      long size;
      try {
        size = Files.size(result.outputPdf());
      } catch (IOException e) {
        throw new UncheckedIOException("Could not read the assembled bundle's size", e);
      }
      if (size > limits.maxOutputBytes()) {
        throw limitExceeded("The finished bundle is " + size + " bytes, which exceeds the "
            + "configured maximum of " + limits.maxOutputBytes() + " bytes.", "maxOutputBytes");
      }
      if (result.totalPages() > limits.maxTotalPages()) {
        throw limitExceeded("The finished bundle has " + result.totalPages() + " pages, which "
            + "exceeds the configured maximum of " + limits.maxTotalPages() + " pages.",
            "maxTotalPages");
      }
      return new AssemblyOutcome(result, mapped.origins(), size);
    }

    private BundleGenerationException limitExceeded(String message, String limit) {
      return new BundleGenerationException(BundleErrorCode.LIMIT_EXCEEDED, BundleStage.ASSEMBLE,
          message, "Split the bundle or raise BundleLimits." + limit + " with evidence.",
          List.of());
    }

    private BundleResult buildResult(AssemblyOutcome assembly, Map<String, Converted> converted) {
      AssemblyResult result = assembly.result();
      List<DocumentResult> documents = new ArrayList<>();
      for (int i = 0; i < assembly.origins().size(); i++) {
        BundleDocument origin = assembly.origins().get(i).document();
        if (origin == null) {
          continue;
        }
        Converted convertedDocument = converted.get(origin.id());
        documents.add(new DocumentResult(origin.id(), origin.reference(),
            convertedDocument.mediaType(), convertedDocument.sha256(),
            result.items().get(i).pageCount(), result.items().get(i).startPage()));
      }
      FileArtifact artifact = new FileArtifact(result.outputPdf(), request.fileName(),
          assembly.size(), sha256Of(result.outputPdf()), result.totalPages());
      Path directory = jobDirectory;
      return new BundleResult(artifact, warnings, documents, Map.copyOf(timings),
          () -> JobDirectory.deleteRecursively(directory));
    }

    private void addWarning(BundleWarning warning) {
      warnings.add(warning);
      metrics.warning(warning.code());
      log.warn("{}: {}", warning.code(), warning.message());
    }

    private <T> T timedStage(BundleStage stage, Supplier<T> body) {
      MDC.put(MDC_STAGE, stage.name());
      long start = System.nanoTime();
      try {
        return body.get();
      } finally {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        timings.put(stage, elapsed);
        metrics.stage(stage, elapsed);
      }
    }

    private static String describe(Map<BundleStage, Duration> timings) {
      StringBuilder text = new StringBuilder("{");
      timings.forEach((stage, duration) -> text.append(text.length() > 1 ? ", " : "")
          .append(stage).append('=').append(duration.toMillis()).append("ms"));
      return text.append('}').toString();
    }
  }

  private record AssemblyOutcome(
      AssemblyResult result, List<AssemblyMapping.Origin> origins, long size) {
  }

  private static String sha256Of(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
        in.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JVM algorithm", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not checksum " + file.getFileName(), e);
    }
  }
}
