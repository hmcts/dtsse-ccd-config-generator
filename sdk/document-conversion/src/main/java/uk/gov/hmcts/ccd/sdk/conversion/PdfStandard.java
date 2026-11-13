package uk.gov.hmcts.ccd.sdk.conversion;

/** The PDF standard print-ready output conforms to. */
public enum PdfStandard {
  /** No conformance claim. */
  NONE,
  /**
   * PDF/X-1a:2001 (ISO 15930-1): PDF 1.3, CMYK or grey only, no transparency, an output intent,
   * a trim box on every page, and the PDF/X identification keys in the document information.
   */
  PDF_X_1A_2001
}
