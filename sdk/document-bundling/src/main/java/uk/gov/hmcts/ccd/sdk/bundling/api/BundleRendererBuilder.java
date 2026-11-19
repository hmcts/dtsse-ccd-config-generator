package uk.gov.hmcts.ccd.sdk.bundling.api;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.bundling.render.DefaultBundleRenderer;
import uk.gov.hmcts.ccd.sdk.conversion.DocumentConverter;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderService;

/**
 * Builds a {@link BundleRenderer}.
 *
 * <p>Defaults reproduce the output of the current stitching microservice: PDF passthrough, image
 * conversion, Docmosis-backed office conversion when Docmosis is configured, generated media link
 * pages, and the court-default presentation. Documents are converted to PDF by document-conversion's
 * {@link DocumentConverter}; to bundle more file types, give the renderer a converter with extra
 * {@code PdfConverter}s registered.
 */
public final class BundleRendererBuilder {

  private final Map<String, DocumentResolver> resolvers = new LinkedHashMap<>();
  private final Map<String, Path> watermarkImages = new LinkedHashMap<>();
  private DocmosisRenderService docmosis;
  private DocumentConverter converter;
  private BundleLimits limits = BundleLimits.defaults();
  private int maxConcurrentRenders = 2;
  private MeterRegistry meterRegistry;
  private Path tempDirectory;

  BundleRendererBuilder() {
  }

  /** Registers a consumer document resolver. Required; repeatable for multiple providers. */
  public BundleRendererBuilder resolver(DocumentResolver resolver) {
    Validate.requireNonNull(resolver, "resolver");
    String provider = Validate.requireNonBlank(resolver.provider(), "resolver.provider()");
    if (resolvers.putIfAbsent(provider, resolver) != null) {
      throw new IllegalArgumentException(
          "A DocumentResolver is already registered for provider '" + provider + "'");
    }
    return this;
  }


  /**
   * The converter that turns each fetched document into PDF. Optional: by default the renderer
   * builds one with the built-in converters, using the {@link #docmosis Docmosis client} for
   * office formats and the renderer's {@link #tempDirectory temp directory}. Supply one to bundle
   * file types that need a registered {@code PdfConverter}.
   */
  public BundleRendererBuilder converter(DocumentConverter converter) {
    this.converter = Validate.requireNonNull(converter, "converter");
    return this;
  }

  /**
   * Configures the shared Docmosis render service. It renders cover-page templates and, unless a
   * {@link #converter converter} is supplied, converts office formats. Without it, a bundle with
   * an office document or a cover page fails with a descriptive error.
   */
  public BundleRendererBuilder docmosis(DocmosisRenderService docmosis) {
    this.docmosis = Validate.requireNonNull(docmosis, "docmosis");
    return this;
  }

  /**
   * Registers an approved watermark image (PNG or JPEG) under a name that
   * {@link WatermarkPreset#imageName()} refers to. The file must exist; repeatable.
   */
  public BundleRendererBuilder watermarkImage(String name, Path image) {
    Validate.requireNonBlank(name, "watermarkImage.name");
    Validate.requireNonNull(image, "watermarkImage.image");
    if (!Files.isRegularFile(image)) {
      throw new IllegalArgumentException(
          "Watermark image '" + name + "' is not a readable file: " + image);
    }
    if (watermarkImages.putIfAbsent(name, image) != null) {
      throw new IllegalArgumentException(
          "A watermark image named '" + name + "' is already registered");
    }
    return this;
  }

  /** Overrides the default limits. */
  public BundleRendererBuilder limits(BundleLimits limits) {
    this.limits = Validate.requireNonNull(limits, "limits");
    return this;
  }

  /**
   * Caps the number of bundles this renderer will render concurrently; excess submissions queue.
   * The default is deliberately small because rendering runs in the consumer's JVM.
   */
  public BundleRendererBuilder maxConcurrentRenders(int maxConcurrentRenders) {
    if (maxConcurrentRenders < 1) {
      throw new IllegalArgumentException("maxConcurrentRenders must be at least 1");
    }
    this.maxConcurrentRenders = maxConcurrentRenders;
    return this;
  }


  /**
   * Sets the base directory under which each render creates its owner-only, job-scoped
   * temporary directory. Defaults to {@code java.io.tmpdir}. Every spooled source, handler
   * output, PDFBox spill file, and the assembled PDF live under the job directory, which is
   * removed when the render finishes — on success, failure, and timeout alike.
   */
  public BundleRendererBuilder tempDirectory(Path tempDirectory) {
    this.tempDirectory = Validate.requireNonNull(tempDirectory, "tempDirectory");
    return this;
  }

  /** Publishes the ccd.bundling.* meters to the given Micrometer registry. Optional. */
  public BundleRendererBuilder meterRegistry(MeterRegistry meterRegistry) {
    this.meterRegistry = Validate.requireNonNull(meterRegistry, "meterRegistry");
    return this;
  }

  /**
   * Builds the renderer, validating the required ports.
   */
  public BundleRenderer build() {
    if (resolvers.isEmpty()) {
      throw new IllegalStateException(
          "A DocumentResolver is required: call resolver(...) with the port that turns your "
              + "DocumentReferences into content");
    }
    return new DefaultBundleRenderer(
        resolvers, docmosis, converter != null ? converter : defaultConverter(), limits,
        maxConcurrentRenders, meterRegistry, tempDirectory, watermarkImages);
  }

  private DocumentConverter defaultConverter() {
    DocumentConverter.Builder builder = DocumentConverter.builder();
    if (docmosis != null) {
      builder.docmosis(docmosis);
    }
    if (tempDirectory != null) {
      builder.tempDirectory(tempDirectory);
    }
    return builder.build();
  }
}
