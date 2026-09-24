package uk.gov.hmcts.ccd.sdk.api.external;

import java.util.List;

/** An external event's refusal to start or to accept a submission, with the errors the frontend is sent. */
public record ExternalRejection<T>(List<String> errors) implements ExternalStartResponse<T>, ExternalSubmitResponse<T> {

  public ExternalRejection {
    if (errors.isEmpty()) {
      throw new IllegalArgumentException("A rejection needs at least one error");
    }
    errors = List.copyOf(errors);
  }
}
