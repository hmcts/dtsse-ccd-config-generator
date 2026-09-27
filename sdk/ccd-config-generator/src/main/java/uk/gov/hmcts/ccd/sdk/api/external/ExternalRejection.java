package uk.gov.hmcts.ccd.sdk.api.external;

import java.util.List;

/**
 * An external event's refusal to start or to accept a submission, with the errors the frontend is
 * sent. A handler returns one, or throws {@link #because(String...)} from deeper code.
 */
public record ExternalRejection<T>(List<String> errors) implements ExternalStartResponse<T>, ExternalSubmitResponse<T> {

  public ExternalRejection {
    if (errors.isEmpty()) {
      throw new IllegalArgumentException("A rejection needs at least one error");
    }
    errors = List.copyOf(errors);
  }

  /**
   * An exception to throw from anywhere in a handler: {@code throw ExternalRejection.because("...")}.
   */
  public static ExternalRejectionException because(String... errors) {
    return new ExternalRejectionException(new ExternalRejection<>(List.of(errors)));
  }
}
