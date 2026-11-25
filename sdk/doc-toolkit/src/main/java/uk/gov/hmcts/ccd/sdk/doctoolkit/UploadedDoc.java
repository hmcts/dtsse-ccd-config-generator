package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.List;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * A rendered document stored in CDAM and attached to its case.
 *
 * @param document the value to put in a CCD document field
 * @param pageCount the number of pages
 * @param failures the documents that could not be included
 */
public record UploadedDoc(Document document, int pageCount, List<DocFailure> failures) {

  /** Copies the failures. */
  public UploadedDoc {
    Validate.requireNonNull(document, "UploadedDoc.document");
    failures = List.copyOf(failures);
  }
}
