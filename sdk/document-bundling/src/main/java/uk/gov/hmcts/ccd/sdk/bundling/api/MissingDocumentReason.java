package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Why a document was left out of a bundle, in terms a reader of the bundle can act on. The
 * message is printed on the document's placeholder page and is suitable for showing to users;
 * the technical detail is in {@link MissingDocument#detail()}.
 */
public enum MissingDocumentReason {

  /** The document no longer exists where it was stored. */
  NOT_FOUND("The document could not be found. It may have been deleted."),

  /** The bundling service was not allowed to open the document. */
  ACCESS_DENIED("The bundling service was not given permission to open the document."),

  /** The document's storage could not be reached, even after retrying. */
  UNAVAILABLE("The document could not be retrieved because of a temporary technical problem. "
      + "It will be included when the bundle is next updated."),

  /** The document's file type cannot be included in a bundle. */
  UNSUPPORTED_FORMAT("This type of file cannot be included in a bundle."),

  /** The document could not be converted to PDF. */
  CONVERSION_FAILED("The document could not be converted to PDF."),

  /** The file could not be opened: it is damaged, password protected or not what it claims. */
  UNREADABLE("The file could not be opened. It may be damaged or password protected."),

  /** The document is larger than a bundle allows. */
  TOO_LARGE("The document is too large to include in the bundle.");

  private final String message;

  MissingDocumentReason(String message) {
    this.message = message;
  }

  /** A plain-English explanation, safe to show to users. */
  public String message() {
    return message;
  }

  /** The reason for a document failure with this error code. */
  public static MissingDocumentReason of(BundleErrorCode code) {
    return switch (code) {
      case DOCUMENT_NOT_FOUND -> NOT_FOUND;
      case DOCUMENT_ACCESS_DENIED -> ACCESS_DENIED;
      case MEDIA_TYPE_UNSUPPORTED -> UNSUPPORTED_FORMAT;
      case DOCUMENT_CONVERSION_FAILED, DOCMOSIS_NOT_CONFIGURED -> CONVERSION_FAILED;
      case DOCUMENT_CONTENT_INVALID, DOCUMENT_INSPECTION_FAILED -> UNREADABLE;
      case LIMIT_EXCEEDED -> TOO_LARGE;
      default -> UNAVAILABLE;
    };
  }
}
