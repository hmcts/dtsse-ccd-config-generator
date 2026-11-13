package uk.gov.hmcts.ccd.sdk.conversion;

/** The process colour space of print-ready output. Both are permitted by PDF/X-1a. */
public enum PrintColour {
  /** DeviceCMYK. Colour is kept; RGB is separated with a neutral formula (black stays K only). */
  CMYK,
  /** DeviceGray. For mono print runs; files are about a quarter the size of CMYK. */
  GRAYSCALE
}
