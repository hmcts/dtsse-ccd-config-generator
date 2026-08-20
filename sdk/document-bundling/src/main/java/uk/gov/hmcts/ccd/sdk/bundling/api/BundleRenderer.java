package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Set;

/**
 * The synchronous, storage-agnostic rendering engine: validates a {@link BundleRequest}, resolves
 * its documents through the consumer's {@link DocumentResolver}, converts each to PDF through the
 * per-media-type handler registry, assembles the bundle, and publishes it through the consumer's
 * {@link BundleDestination}.
 *
 * <p>The renderer knows nothing of users, hearings, case events, HTTP callbacks, or job tables,
 * so the same request renders from a CCD user event, an application command, a scheduled task, or
 * a test. Every document in a request must stitch; there is no partial bundle. Construction
 * follows the Jackson pattern: {@link #builder()} with complete defaults plus optional
 * {@link BundlingExtension}s.
 */
public interface BundleRenderer {

  /**
   * Starts building a renderer with default behaviour equivalent to the current stitching
   * microservice.
   *
   * @return a new builder
   */
  static BundleRendererBuilder builder() {
    return new BundleRendererBuilder();
  }

  /**
   * Renders one bundle synchronously and publishes it atomically.
   *
   * @param request the bundle to generate
   * @param context the consumer context passed through to resolvers and the destination
   * @return the generation report for the published bundle
   * @throws BundleGenerationException if anything prevents publishing, naming each responsible
   *     document and its typed reason; nothing is published on failure
   */
  BundleResult render(BundleRequest request, BundleExecutionContext context);

  /**
   * The media types the effective handler registry supports, for inspection and error messages.
   *
   * @return the handled media types
   */
  Set<String> handledMediaTypes();

  /**
   * The limits this renderer enforces, exposed so callers that wrap the renderer — the durable
   * job worker validating its lease duration against {@link BundleLimits#maxElapsed()}, for
   * example — can read the effective configuration.
   *
   * <p>Renderers built by {@link #builder()} return the configured limits. The default
   * implementation returns {@link BundleLimits#defaults()} so existing test doubles keep
   * compiling; any implementation that enforces non-default limits must override it.
   *
   * @return the effective limits
   */
  default BundleLimits limits() {
    return BundleLimits.defaults();
  }
}
