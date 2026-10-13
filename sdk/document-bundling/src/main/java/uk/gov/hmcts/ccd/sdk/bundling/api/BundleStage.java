package uk.gov.hmcts.ccd.sdk.bundling.api;

/** The stages of the rendering pipeline, used in structured logs and typed errors. */
public enum BundleStage {
  /** Request validation, before any content is read. */
  VALIDATE,
  /** Batched resolution and spooling of every unique source reference. */
  RESOLVE,
  /** Per-media-type conversion of each source to PDF. */
  CONVERT,
  /** Readability inspection of every converted PDF before assembly. */
  INSPECT,
  /** Assembly of the bundle: generated pages, contents, bookmarks, marks, pagination. */
  ASSEMBLE
}
