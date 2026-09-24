package uk.gov.hmcts.ccd.sdk.api.external;

import java.util.List;

/**
 * The outcome of an external event's submission: accepted, with the summary and description the
 * case history shows for it and optionally a state to move the case to, or rejected with errors
 * that change nothing.
 */
public sealed interface ExternalSubmitResponse<S> permits ExternalSubmitResponse.Accepted, ExternalRejection {

  static <S> Accepted<S> accepted(String summary, String description) {
    return new Accepted<>(summary, description, null);
  }

  static <S> ExternalSubmitResponse<S> rejected(String... errors) {
    return new ExternalRejection<>(List.of(errors));
  }

  record Accepted<S>(String summary, String description, S state) implements ExternalSubmitResponse<S> {

    /** The same outcome, also moving the case to this state. */
    public Accepted<S> movingTo(S newState) {
      return new Accepted<>(summary, description, newState);
    }
  }
}
