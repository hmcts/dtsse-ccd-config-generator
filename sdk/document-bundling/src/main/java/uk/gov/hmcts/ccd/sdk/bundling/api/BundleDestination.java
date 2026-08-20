package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Publishes the finished bundle and returns the stored document's links.
 *
 * <p>Where the PDF's bytes live is an invariant, not a consumer decision: in production the
 * artifact is always uploaded to CDAM — the centralised document blob store — by the SDK's
 * built-in CDAM destination, exactly as the current stitching service publishes its output. This
 * port exists as the seam that keeps the renderer testable: tests and local runs substitute a
 * filesystem implementation. What a consumer owns is the returned metadata — where the
 * {@link StoredBundle#toDocument() Document} is persisted, with which category, classification,
 * and ACLs.
 *
 * <p>Publication is atomic: this port is called only after validation and rendering complete, and
 * it must return only after storage succeeds, so a failed job never replaces the last successful
 * bundle. The upload classification must be explicit — a bundle containing restricted material
 * must not default to public.
 */
@FunctionalInterface
public interface BundleDestination {

  /**
   * Stores the finished artifact and returns its stored reference.
   *
   * @param artifact the validated, finished bundle artifact
   * @param context the execution context for authorisation and correlation
   * @return the stored bundle links and metadata
   */
  StoredBundle store(BundleArtifact artifact, BundleExecutionContext context);
}
