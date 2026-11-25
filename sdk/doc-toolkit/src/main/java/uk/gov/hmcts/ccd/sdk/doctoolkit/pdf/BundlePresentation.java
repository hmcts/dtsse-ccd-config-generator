package uk.gov.hmcts.ccd.sdk.doctoolkit.pdf;

import uk.gov.hmcts.ccd.sdk.doctoolkit.PageNumbers;

/**
 * What the assembler adds around the documents.
 *
 * @param confidentialMarking whether confidential documents' pages get the approved header
 */
public record BundlePresentation(
    boolean tableOfContents,
    boolean sectionCoverSheets,
    boolean documentCoverSheets,
    PageNumbers pageNumbers,
    boolean confidentialMarking) {

  public BundlePresentation {
    Checks.requireNonNull(pageNumbers, "BundlePresentation.pageNumbers");
  }

  /**
   * Contents, section cover sheets, {@code N of M} page numbers and confidential marking.
   */
  public static BundlePresentation courtDefault() {
    return new BundlePresentation(true, true, false, PageNumbers.BOTTOM_CENTRE_N_OF_M, true);
  }

  /** Nothing added: documents joined as they are. */
  public static BundlePresentation bare() {
    return new BundlePresentation(false, false, false, PageNumbers.NONE, false);
  }

  public BundlePresentation withTableOfContents(boolean enabled) {
    return new BundlePresentation(enabled, sectionCoverSheets, documentCoverSheets, pageNumbers,
        confidentialMarking);
  }

  public BundlePresentation withSectionCoverSheets(boolean enabled) {
    return new BundlePresentation(tableOfContents, enabled, documentCoverSheets, pageNumbers,
        confidentialMarking);
  }

  public BundlePresentation withDocumentCoverSheets(boolean enabled) {
    return new BundlePresentation(tableOfContents, sectionCoverSheets, enabled, pageNumbers,
        confidentialMarking);
  }

  public BundlePresentation withPageNumbers(PageNumbers preset) {
    return new BundlePresentation(tableOfContents, sectionCoverSheets, documentCoverSheets, preset,
        confidentialMarking);
  }

  public BundlePresentation withConfidentialMarking(boolean enabled) {
    return new BundlePresentation(tableOfContents, sectionCoverSheets, documentCoverSheets,
        pageNumbers, enabled);
  }
}
