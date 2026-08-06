package uk.gov.hmcts.ccd.sdk;

import java.util.Optional;

public record SystemCaseEventOutcome<S>(
    Optional<S> state,
    Optional<String> summary,
    Optional<String> description
) {

  public SystemCaseEventOutcome {
    state = state == null ? Optional.empty() : state;
    summary = summary == null ? Optional.empty() : summary;
    description = description == null ? Optional.empty() : description;
  }

  public static <S> SystemCaseEventOutcome<S> noStateChange() {
    return new SystemCaseEventOutcome<>(Optional.empty(), Optional.empty(), Optional.empty());
  }

  public static <S> SystemCaseEventOutcome<S> transitionTo(S state) {
    return new SystemCaseEventOutcome<>(Optional.of(state), Optional.empty(), Optional.empty());
  }
}
