package uk.gov.hmcts.ccd.sdk.api.external;

import java.util.List;

/** What an external event answers when its frontend starts it: a payload, or why it cannot start. */
public sealed interface ExternalStartResponse<O> permits ExternalStartResponse.Started, ExternalRejection {

  static <O> ExternalStartResponse<O> started(O payload) {
    return new Started<>(payload);
  }

  static <O> ExternalStartResponse<O> rejected(String... errors) {
    return new ExternalRejection<>(List.of(errors));
  }

  record Started<O>(O payload) implements ExternalStartResponse<O> {
  }
}
