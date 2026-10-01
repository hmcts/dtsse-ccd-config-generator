package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * A versioned presentation preset for the generated bundle.
 *
 * <p>Presentation is deliberately constrained: consumers choose from approved options rather than
 * placing arbitrary text or graphics over evidence pages. {@link #courtDefault()} reproduces the
 * output of the current stitching microservice.
 */
public record BundlePresentation(
    boolean tableOfContents,
    boolean sectionCoverSheets,
    boolean documentCoverSheets,
    PageNumbers pageNumbers,
    ConfidentialMarking confidentialMarking) {

  public BundlePresentation {
    Validate.requireNonNull(pageNumbers, "BundlePresentation.pageNumbers");
    Validate.requireNonNull(confidentialMarking, "BundlePresentation.confidentialMarking");
  }

  /**
   * The default court presentation: table of contents, section cover sheets, {@code N of M} page
   * numbers centred in the footer, and the approved confidential header marking.
   */
  public static BundlePresentation courtDefault() {
    return new BundlePresentation(
        true, true, false, PageNumbers.BOTTOM_CENTRE_N_OF_M, ConfidentialMarking.APPROVED_HEADER);
  }

  /** Returns a copy with the table of contents enabled or disabled. */
  public BundlePresentation withTableOfContents(boolean enabled) {
    return new BundlePresentation(
        enabled, sectionCoverSheets, documentCoverSheets, pageNumbers, confidentialMarking);
  }

  /** Returns a copy with section cover sheets enabled or disabled. */
  public BundlePresentation withSectionCoverSheets(boolean enabled) {
    return new BundlePresentation(
        tableOfContents, enabled, documentCoverSheets, pageNumbers, confidentialMarking);
  }

  /** Returns a copy with document cover sheets enabled or disabled. */
  public BundlePresentation withDocumentCoverSheets(boolean enabled) {
    return new BundlePresentation(
        tableOfContents, sectionCoverSheets, enabled, pageNumbers, confidentialMarking);
  }

  /** Returns a copy with the given page-number preset. */
  public BundlePresentation withPageNumbers(PageNumbers preset) {
    return new BundlePresentation(
        tableOfContents, sectionCoverSheets, documentCoverSheets, preset, confidentialMarking);
  }

  /** Returns a copy with the given confidential marking. */
  public BundlePresentation withConfidentialMarking(ConfidentialMarking marking) {
    return new BundlePresentation(
        tableOfContents, sectionCoverSheets, documentCoverSheets, pageNumbers, marking);
  }
}
