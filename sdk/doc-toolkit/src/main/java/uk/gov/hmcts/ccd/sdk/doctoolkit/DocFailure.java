package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A document that could not be included, and why. With {@link MissingDocuments#PLACEHOLDER} its
 * place is taken by a page giving {@link Reason#message()}.
 *
 * @param id the part's id, when the caller gave one
 * @param title the part's title
 * @param sectionPath the titles of the sections holding it, outermost first, starting with the
 *     request's title; empty when the failure is not of a part in a section
 * @param page the placeholder's page in the output, when one was made
 * @param reason why it could not be included
 * @param detail what went wrong, for logs and support; never shown to readers
 */
public record DocFailure(Optional<String> id, String title, List<String> sectionPath,
    OptionalInt page, Reason reason, String detail) {

  /** Validates the fields. */
  public DocFailure {
    Validate.requireNonNull(id, "DocFailure.id");
    Validate.requireNonBlank(title, "DocFailure.title");
    sectionPath = List.copyOf(Validate.requireNonNull(sectionPath, "DocFailure.sectionPath"));
    Validate.requireNonNull(page, "DocFailure.page");
    Validate.requireNonNull(reason, "DocFailure.reason");
    detail = detail == null ? "" : detail;
  }

  /** A failure with no id, section or page: of a whole request, or before assembly. */
  public DocFailure(String title, Reason reason, String detail) {
    this(Optional.empty(), title, List.of(), OptionalInt.empty(), reason, detail);
  }

  /** This failure with the page its placeholder landed on. */
  public DocFailure onPage(int page) {
    return new DocFailure(id, title, sectionPath, OptionalInt.of(page), reason, detail);
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
