package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * How documents flagged {@link BundleDocument#confidential()} are visibly marked in the output.
 */
public enum ConfidentialMarking {

  /** No visible marking; confidential flags still appear in the table of contents metadata. */
  NONE,

  /** The standard approved header marking on each page of a confidential document. */
  APPROVED_HEADER
}
