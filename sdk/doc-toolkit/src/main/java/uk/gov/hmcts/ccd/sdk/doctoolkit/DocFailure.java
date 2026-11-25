package uk.gov.hmcts.ccd.sdk.doctoolkit;

/**
 * A document that could not be included, and why. In a bundle that uses
 * {@link MissingDocuments#PLACEHOLDER} its place is taken by a page giving {@link Reason#message()}.
 *
 * @param title the document's title (or its source's name)
 * @param reason why it could not be included
 * @param detail what went wrong, for logs and support; never shown to readers of the bundle
 */
public record DocFailure(String title, Reason reason, String detail) {

  /** Validates the fields. */
  public DocFailure {
    Validate.requireNonBlank(title, "DocFailure.title");
    Validate.requireNonNull(reason, "DocFailure.reason");
    detail = detail == null ? "" : detail;
  }

  /** Why a document could not be included. */
  public enum Reason {
    /** The document does not exist, or no longer does. */
    NOT_FOUND("The document could not be found. It may have been deleted."),
    /** The system user may not read it. */
    ACCESS_DENIED("The service was not given permission to open the document."),
    /** It could not be fetched or converted for a reason that may pass, such as an outage. */
    UNAVAILABLE("The document could not be retrieved because of a temporary technical problem."),
    /** Its type cannot be converted. */
    UNSUPPORTED_FORMAT("This type of file cannot be included."),
    /** Conversion failed. */
    CONVERSION_FAILED("The document could not be converted to PDF."),
    /** Its content is damaged, encrypted or not what its type says. */
    UNREADABLE("The file could not be opened. It may be damaged or password protected."),
    /** It is larger than the toolkit will fetch. */
    TOO_LARGE("The document is too large to include.");

    private final String message;

    Reason(String message) {
      this.message = message;
    }

    /** What the placeholder page says, suitable for anyone reading the bundle. */
    public String message() {
      return message;
    }

    /** Whether trying again later may succeed. */
    public boolean isTransient() {
      return this == UNAVAILABLE;
    }
  }
}
