package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.bundling.convert.DocmosisOfficeHandler;
import uk.gov.hmcts.ccd.sdk.bundling.convert.ImageHandler;
import uk.gov.hmcts.ccd.sdk.bundling.convert.PdfPassthroughHandler;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.render.DefaultBundleRenderer;

/**
 * Builds a {@link BundleRenderer}.
 *
 * <p>Defaults reproduce the output of the current stitching microservice: PDF passthrough, image
 * conversion, Docmosis-backed office conversion when Docmosis is configured, and the court-default
 * presentation. Extensions apply in registration order on top of the built-ins.
 */
public final class BundleRendererBuilder {

  private final Map<String, DocumentResolver> resolvers = new LinkedHashMap<>();
  private final List<BundlingExtension> extensions = new ArrayList<>();
  private DocmosisRenderService docmosis;
  private BundleLimits limits = BundleLimits.defaults();
  private int maxConcurrentRenders = 2;
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
   * Registers an extension module. Repeatable; extensions apply in registration order, so the
   * last registration for a media type wins.
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
   */
  public BundleRendererBuilder docmosis(DocmosisRenderService docmosis) {
    this.docmosis = Validate.requireNonNull(docmosis, "docmosis");
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

  /**
   * Builds the renderer, validating the required ports and applying extensions to the built-in
   * handler registry.
   */
  public BundleRenderer build() {
    if (resolvers.isEmpty()) {
      throw new IllegalStateException(
          "A DocumentResolver is required: call resolver(...) with the port that turns your "
              + "DocumentReferences into content");
    }
    HandlerRegistry registry = HandlerRegistry.create(builtInHandlers(), extensions);
    return new DefaultBundleRenderer(
        resolvers, docmosis, registry, limits, maxConcurrentRenders, tempDirectory);
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
    return builtIns;
  }
}
