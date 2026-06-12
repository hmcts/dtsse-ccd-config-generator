package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.util.Set;

final class TaskOutboxFailureClassifier {

  // These client responses represent timeouts, conflicts, early requests, or rate limiting.
  private static final Set<Integer> RECOVERABLE_CLIENT_STATUSES = Set.of(408, 409, 425, 429);

  private TaskOutboxFailureClassifier() {
  }

  static boolean isRecoverable(Integer statusCode) {
    // Missing and unexpected non-error status codes are ambiguous, so allow retries to exhaust.
    if (statusCode == null || statusCode < 400) {
      return true;
    }
    // Other 4xx responses are deterministic request/authentication failures from task-management-api.
    return statusCode >= 500 || RECOVERABLE_CLIENT_STATUSES.contains(statusCode);
  }
}
