package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Set;

/**
 * The synchronous rendering entry point: a {@link BundleRequest} in, a finished PDF out. Runs on
 * the caller's thread; has no knowledge of users, case events or job tables.
 */
public interface BundleRenderer {

  static BundleRendererBuilder builder() {
    return new BundleRendererBuilder();
  }

  /**
   * Renders the bundle. Every document in the request is stitched or the render fails with a
   * {@link BundleGenerationException} naming the documents responsible; there is no partial
   * bundle.
   */
  BundleResult render(BundleRequest request, BundleExecutionContext context);

  /** The media types with a registered handler, after extensions have applied. */
  Set<String> handledMediaTypes();
}
