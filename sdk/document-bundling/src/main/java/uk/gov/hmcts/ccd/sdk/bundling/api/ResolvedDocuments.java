package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Map;

/**
 * The per-reference outcomes of one batched resolution: content for each success and a typed
 * failure for each reference that could not be resolved.
 *
 * @param resolved successfully resolved documents by reference
 * @param failures typed failures by reference
 */
public record ResolvedDocuments(
    Map<DocumentReference, ResolvedDocument> resolved,
    Map<DocumentReference, ResolutionFailure> failures) {

  public ResolvedDocuments {
    resolved = Map.copyOf(Validate.requireNonNull(resolved, "ResolvedDocuments.resolved"));
    failures = Map.copyOf(Validate.requireNonNull(failures, "ResolvedDocuments.failures"));
  }

  /**
   * Convenience factory for a fully successful resolution.
   *
   * @param resolved successfully resolved documents by reference
   * @return outcomes with no failures
   */
  public static ResolvedDocuments allResolved(Map<DocumentReference, ResolvedDocument> resolved) {
    return new ResolvedDocuments(resolved, Map.of());
  }
}
