package uk.gov.hmcts.ccd.sdk.testing;

import java.util.List;
import java.util.function.Supplier;

/**
 * What came of posting a payload to an external event. An accepted submission has status 200, the
 * case history entry it wrote and the rows it changed; a rejected one has status 422 and the
 * handler's errors; a failed one has the application's HTTP status and response body.
 */
public final class ExternalOutcome {

  private final int status;
  private final String body;
  private final List<String> errors;
  private final CcdEventTestSupport.Audit audit;
  private final Supplier<List<CcdEventTestSupport.RowChange>> changes;

  ExternalOutcome(int status, String body, List<String> errors, CcdEventTestSupport.Audit audit,
                  Supplier<List<CcdEventTestSupport.RowChange>> changes) {
    this.status = status;
    this.body = body;
    this.errors = List.copyOf(errors);
    this.audit = audit;
    this.changes = changes;
  }

  public boolean accepted() {
    return audit != null;
  }

  public int status() {
    return status;
  }

  /** The application's response body, for a failed submission. */
  public String body() {
    return body;
  }

  /** The submit handler's errors, for a rejected submission. */
  public List<String> errors() {
    return errors;
  }

  /** The case history entry an accepted submission wrote. */
  public CcdEventTestSupport.Audit audit() {
    return audit;
  }

  /** Every row an accepted submission changed, in the order it changed them. */
  public List<CcdEventTestSupport.RowChange> changes() {
    return changes.get();
  }

  public List<CcdEventTestSupport.RowChange> changes(String table) {
    return changes().stream().filter(change -> change.table().equals(table)).toList();
  }

  @Override
  public String toString() {
    return accepted() ? "accepted" : errors.isEmpty() ? "HTTP " + status + (body == null ? "" : ": " + body)
        : "errors " + errors;
  }
}
