package uk.gov.hmcts.ccd.sdk.api.external;

import java.util.List;

/** What an external event answers when its frontend starts it: a payload, or why it cannot start. */
public sealed interface ExternalStartResponse<O> {

  static <O> ExternalStartResponse<O> started(O payload) {
    return new Started<>(payload);
  }

  static <O> ExternalStartResponse<O> rejected(String... errors) {
    return new Rejected<>(List.of(errors));
  }

  record Started<O>(O payload) implements ExternalStartResponse<O> {
  }

  record Rejected<O>(List<String> errors) implements ExternalStartResponse<O> {

    public Rejected {
      if (errors.isEmpty()) {
        throw new IllegalArgumentException("A rejected start needs at least one error");
      }
      errors = List.copyOf(errors);
    }
  }
}
