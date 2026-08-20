package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.List;

/**
 * Consumer port that turns opaque {@link DocumentReference}s into content.
 *
 * <p>Resolution is batched so a consumer can authorise and fetch the whole requested set
 * efficiently — for example one preflight access check, or a bulk local database read. The SDK
 * deduplicates identical references before calling this port and spools each resolution once, so
 * the same source can appear at multiple positions in a bundle without another fetch.
 *
 * <p>A resolver must authorise the whole set for the given execution context, must map failures
 * to typed {@link ResolutionFailureReason}s rather than throwing raw downstream errors, and must
 * never persist authorisation material.
 */
public interface DocumentResolver {

  /**
   * The provider name this resolver serves; matched against
   * {@link DocumentReference#provider()}.
   *
   * @return the provider name
   */
  String provider();

  /**
   * Resolves every reference in the batch, returning content for each success and a typed
   * failure for each reference that could not be resolved.
   *
   * @param references the deduplicated references to resolve
   * @param context the execution context for authorisation and correlation
   * @return the per-reference outcomes
   */
  ResolvedDocuments resolveAll(List<DocumentReference> references, BundleExecutionContext context);
}
