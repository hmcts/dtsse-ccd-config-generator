package uk.gov.hmcts.ccd.sdk.api.external;

import java.util.List;

/**
 * Rejects an external event from code below its handler, where returning a rejection is awkward.
 * Thrown from a start or submit handler it has the same effect as returning
 * {@link ExternalRejection}: the frontend is sent the errors, and a submission's writes are rolled
 * back. Create one with {@link ExternalRejection#because(String...)}.
 */
public class ExternalRejectionException extends RuntimeException {

  private final ExternalRejection<?> rejection;

  ExternalRejectionException(ExternalRejection<?> rejection) {
    super(String.join("; ", rejection.errors()));
    this.rejection = rejection;
  }

  public List<String> errors() {
    return rejection.errors();
  }
}
