package uk.gov.hmcts.ccd.sdk.retention;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class RetainAndDisposeFailures {

  private final List<Failure> failures = new ArrayList<>();

  void attempt(long caseReference, String operation, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException exception) {
      log.error("Retain and dispose operation failed operation={} caseReference={}",
          operation, caseReference, exception);
      failures.add(new Failure(caseReference, operation, exception));
    }
  }

  void throwIfAny() {
    if (failures.isEmpty()) {
      return;
    }
    String summary = failures.stream()
        .map(failure -> failure.caseReference() + " (" + failure.operation() + ")")
        .collect(Collectors.joining(", "));
    IllegalStateException aggregate = new IllegalStateException(
        "Retain and dispose failed for " + failures.size() + " case operations: " + summary,
        failures.getFirst().exception()
    );
    failures.stream().skip(1).map(Failure::exception).forEach(aggregate::addSuppressed);
    throw aggregate;
  }

  private record Failure(long caseReference, String operation, RuntimeException exception) {
  }
}
