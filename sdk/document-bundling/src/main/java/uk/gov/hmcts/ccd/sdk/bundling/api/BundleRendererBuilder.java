package uk.gov.hmcts.ccd.sdk.bundling.api;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.bundling.convert.DocmosisOfficeHandler;
import uk.gov.hmcts.ccd.sdk.bundling.convert.ImageHandler;
import uk.gov.hmcts.ccd.sdk.bundling.convert.MediaLinkHandler;
import uk.gov.hmcts.ccd.sdk.bundling.convert.PdfPassthroughHandler;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.render.DefaultBundleRenderer;

/**
 * Builds a {@link BundleRenderer}.
 *
 * <p>Defaults reproduce the output of the current stitching microservice: PDF passthrough, image
 * conversion, Docmosis-backed office conversion when Docmosis is configured, generated media link
 * pages, and the court-default presentation. Extensions apply in registration order on top of the
 * built-ins.
 */
public final class BundleRendererBuilder {

  private final Map<String, DocumentResolver> resolvers = new LinkedHashMap<>();
  private final List<BundlingExtension> extensions = new ArrayList<>();
  private BundleDestination destination;
  private DocmosisRenderService docmosis;
  private BundleLimits limits = BundleLimits.defaults();
  private int maxConcurrentRenders = 2;
  private MeterRegistry meterRegistry;
  private Path tempDirectory;

  BundleRendererBuilder() {
  }

  /**
   * Registers a consumer document resolver. Required; repeatable for multiple providers.
   *
   * @param resolver the resolver to register
   * @return this builder
   * @throws IllegalArgumentException if a resolver is already registered for the same provider
   */
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
   * Sets the destination that publishes the finished bundle. Required. Production wiring uses
   * the SDK's built-in CDAM destination — artifact storage is an invariant — while tests and
   * local runs substitute a filesystem implementation.
   *
   * @param destination the destination port
   * @return this builder
   */
  public BundleRendererBuilder destination(BundleDestination destination) {
    this.destination = Validate.requireNonNull(destination, "destination");
    return this;
  }

  /**
   * Registers an extension module. Repeatable; extensions apply in registration order, so the
   * last registration for a media type wins.
   *
   * @param extension the extension to register
   * @return this builder
   */
  public BundleRendererBuilder extension(BundlingExtension extension) {
    Validate.requireNonNull(extension, "extension");
    Validate.requireNonBlank(extension.name(), "extension.name()");
    extensions.add(extension);
    return this;
  }

  /**
   * Configures the shared Docmosis render service, enabling the default office-format handlers.
   * Without it, office media types have no handler and a bundle containing one fails with a
   * descriptive error.
   *
   * @param docmosis the Docmosis render service client
   * @return this builder
   */
  public BundleRendererBuilder docmosis(DocmosisRenderService docmosis) {
    this.docmosis = Validate.requireNonNull(docmosis, "docmosis");
    return this;
  }

  /**
   * Overrides the default limits.
   *
   * @param limits the limits to enforce
   * @return this builder
   */
  public BundleRendererBuilder limits(BundleLimits limits) {
    this.limits = Validate.requireNonNull(limits, "limits");
    return this;
  }

  /**
   * Caps the number of bundles this renderer will render concurrently; excess submissions queue.
   * The default is deliberately small because rendering runs in the consumer's JVM.
   *
   * @param maxConcurrentRenders the maximum concurrent renders, at least 1
   * @return this builder
   */
  public BundleRendererBuilder maxConcurrentRenders(int maxConcurrentRenders) {
    if (maxConcurrentRenders < 1) {
      throw new IllegalArgumentException("maxConcurrentRenders must be at least 1");
    }
    this.maxConcurrentRenders = maxConcurrentRenders;
    return this;
  }

  /**
   * Binds the renderer's {@code ccd.bundling.*} metrics — per-stage timers and
   * document/page/byte/warning/failure counters — to the given Micrometer registry. Without it,
   * no metrics are published.
   *
   * @param meterRegistry the consumer's meter registry
   * @return this builder
   */
  public BundleRendererBuilder meterRegistry(MeterRegistry meterRegistry) {
    this.meterRegistry = Validate.requireNonNull(meterRegistry, "meterRegistry");
    return this;
  }

  /**
   * Sets the base directory under which each render creates its owner-only, job-scoped
   * temporary directory. Defaults to {@code java.io.tmpdir}. Every spooled source, handler
   * output, PDFBox spill file, and the assembled PDF live under the job directory, which is
   * removed when the render finishes — on success, failure, and timeout alike.
   *
   * @param tempDirectory the base directory for job temporary directories
   * @return this builder
   */
  public BundleRendererBuilder tempDirectory(Path tempDirectory) {
    this.tempDirectory = Validate.requireNonNull(tempDirectory, "tempDirectory");
    return this;
  }

  /**
   * Builds the renderer, validating the required ports and applying extensions to the built-in
   * handler registry.
   *
   * @return the renderer
   * @throws IllegalStateException if a required port is missing or an extension registration is
   *     invalid
   */
  public BundleRenderer build() {
    if (resolvers.isEmpty()) {
      throw new IllegalStateException(
          "A DocumentResolver is required: call resolver(...) with the port that turns your "
              + "DocumentReferences into content");
    }
    if (destination == null) {
      throw new IllegalStateException(
          "A BundleDestination is required: call destination(...) with the port that stores the "
              + "finished bundle");
    }
    HandlerRegistry registry = HandlerRegistry.create(builtInHandlers(), extensions);
    return new DefaultBundleRenderer(
        resolvers, destination, docmosis, registry, limits, maxConcurrentRenders, meterRegistry,
        tempDirectory);
  }

  private Map<String, DocumentHandler> builtInHandlers() {
    Map<String, DocumentHandler> builtIns = new LinkedHashMap<>();
    builtIns.put(BuiltInMediaTypes.PDF, new PdfPassthroughHandler());
    ImageHandler imageHandler = new ImageHandler();
    for (String type : BuiltInMediaTypes.IMAGES) {
      builtIns.put(type, imageHandler);
    }
    if (docmosis != null) {
      DocmosisOfficeHandler officeHandler = new DocmosisOfficeHandler();
      for (String type : BuiltInMediaTypes.OFFICE) {
        builtIns.put(type, officeHandler);
      }
    }
    MediaLinkHandler mediaLinkHandler = new MediaLinkHandler();
    for (String type : BuiltInMediaTypes.MEDIA) {
      builtIns.put(type, mediaLinkHandler);
    }
    return builtIns;
  }
}
