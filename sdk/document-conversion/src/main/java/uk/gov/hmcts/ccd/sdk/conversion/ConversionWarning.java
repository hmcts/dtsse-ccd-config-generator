package uk.gov.hmcts.ccd.sdk.conversion;

/**
 * Something about the source that did not stop the conversion but that the caller may want to
 * record.
 *
 * @param code what happened
 * @param message a log-safe description naming the file
 */
public record ConversionWarning(Code code, String message) {

  /** What happened. */
  public enum Code {
    /** The declared media type was wrong; the content was converted as its detected type. */
    MEDIA_TYPE_MISMATCH,
    /** No usable media type was declared; it was inferred from the file name's extension. */
    MEDIA_TYPE_INFERRED,
    /** CMYK output was near the size limit, so it was converted in greyscale instead. */
    COLOUR_REDUCED_FOR_SIZE
  }
}
