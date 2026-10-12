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
   * Renders the bundle. Under {@link MissingDocumentPolicy#FAIL} every document in the request
   * is stitched or the render fails with a {@link BundleGenerationException} naming the
   * documents responsible. Under {@link MissingDocumentPolicy#PLACEHOLDER} a document that
   * cannot be included is replaced by a placeholder page and reported in
   * {@link BundleResult#missingDocuments()}. This is a {@link RenderAttempt#FINAL} attempt.
   */
  BundleResult render(BundleRequest request, BundleExecutionContext context);

  /**
   * Renders the bundle as {@link #render(BundleRequest, BundleExecutionContext)}, except that a
   * {@link RenderAttempt#RETRYABLE} attempt fails rather than substitute a placeholder for a
   * source that is only temporarily unavailable.
   */
  default BundleResult render(BundleRequest request, BundleExecutionContext context,
      RenderAttempt attempt) {
    return render(request, context);
  }

  /** The media types with a registered handler, after extensions have applied. */
  Set<String> handledMediaTypes();
}
