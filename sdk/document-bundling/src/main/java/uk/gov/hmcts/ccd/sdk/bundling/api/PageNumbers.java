package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Approved page-number presets: a position combined with a plain {@code N} or {@code N of M}
 * format. Free-form coordinates are deliberately not supported.
 */
public enum PageNumbers {

  /** No page numbers. */
  NONE,

  /**
   * Page number centred in the footer, plain {@code N}.
   */
  BOTTOM_CENTRE_N,

  /**
   * Page number centred in the footer as {@code N of M}.
   */
  BOTTOM_CENTRE_N_OF_M,

  /**
   * Page number in the right footer, plain {@code N}.
   */
  BOTTOM_RIGHT_N,

  /**
   * Page number in the right footer as {@code N of M}.
   */
  BOTTOM_RIGHT_N_OF_M,

  /**
   * Page number in the right header, plain {@code N}.
   */
  TOP_RIGHT_N,

  /**
   * Page number in the right header as {@code N of M}.
   */
  TOP_RIGHT_N_OF_M
}
