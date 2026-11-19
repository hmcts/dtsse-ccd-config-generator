package uk.gov.hmcts.ccd.sdk.conversion;

import java.util.Optional;

/**
 * A document could not be converted. The message is log-safe and names the file; it never
 * includes the Docmosis access key or a raw Docmosis response.
 */
public class ConversionException extends Exception {

  /** Why the conversion failed. */
  public enum Reason {
    /** No converter is registered for the media type. */
    UNSUPPORTED_MEDIA_TYPE,
    /** The content contradicts its declared media type and matches nothing that would explain it. */
    MEDIA_TYPE_CONFLICT,
    /** The content is corrupt, empty, or not what it claims to be. */
    INVALID_CONTENT,
    /** The PDF is encrypted or password protected. */
    ENCRYPTED,
    /** The media type needs Docmosis, and no Docmosis client is configured. */
    CONVERTER_NOT_CONFIGURED,
    /**
     * Docmosis, or a registered {@link PdfConverter}, refused or failed the conversion.
     */
    CONVERTER_FAILED,
    /** The print-ready output is larger than the configured limit, even in greyscale. */
    OUTPUT_TOO_LARGE,
    /** Local disk I/O failed. */
    IO_FAILURE
  }

  private final Reason reason;
  private final boolean transientFailure;
  private final String mediaType;

  /** Creates the exception. */
  public ConversionException(Reason reason, String message) {
    this(reason, message, false, null);
  }

  /** Creates the exception with a cause. */
  public ConversionException(Reason reason, String message, Throwable cause) {
    this(reason, message, false, cause);
  }

  /** Creates the exception, flagging whether a retry could succeed. */
  public ConversionException(
      Reason reason, String message, boolean transientFailure, Throwable cause) {
    this(reason, message, transientFailure, cause, null);
  }

  /** For a failure about the media type itself, naming the type the source was routed as. */
  ConversionException(Reason reason, String message, String mediaType) {
    this(reason, message, false, null, mediaType);
  }

  private ConversionException(Reason reason, String message, boolean transientFailure,
      Throwable cause, String mediaType) {
    super(message, cause);
    this.reason = reason;
    this.transientFailure = transientFailure;
    this.mediaType = mediaType;
  }

  /** Why the conversion failed. */
  public Reason reason() {
    return reason;
  }

  /**
   * For {@link Reason#UNSUPPORTED_MEDIA_TYPE} and {@link Reason#CONVERTER_NOT_CONFIGURED}, the
   * media type the source was routed as after checking its content: empty when it has no known
   * type. Absent for other failures.
   */
  public Optional<String> mediaType() {
    return Optional.ofNullable(mediaType);
  }

  /**
   * Whether retrying the same conversion later could succeed: a Docmosis timeout, connection
   * failure, interruption, rate limit, rejected credentials or 5xx response, or local disk
   * trouble. Everything else is a problem with the document and is permanent.
   */
  public boolean isTransientFailure() {
    return transientFailure;
  }
}
