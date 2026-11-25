package uk.gov.hmcts.ccd.sdk.doctoolkit;

/** What a bundle does with a document that cannot be included. */
public enum MissingDocuments {
  /** The document is replaced by a page saying why, and listed in the result's failures. */
  PLACEHOLDER,
  /**
   * Rendering fails with a {@link DocToolkitException} listing every such document.
   */
  FAIL
}
