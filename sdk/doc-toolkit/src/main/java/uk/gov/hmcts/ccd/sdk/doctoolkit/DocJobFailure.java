package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.List;

/**
 * Why a submitted document could not be made.
 *
 * @param reason a {@link DocToolkitException.Reason} name, {@code HANDLER_FAILED} when the
 *     handler kept failing, {@code CRASHED} when every attempt's worker died, or
 *     {@code UNREADABLE_REQUEST}
 * @param message what went wrong, for logs and support; it may name documents
 * @param documents the documents that could not be included, when that was the reason
 */
public record DocJobFailure(String reason, String message, List<DocFailure> documents) {
  public DocJobFailure {
    documents = List.copyOf(documents);
  }
}
