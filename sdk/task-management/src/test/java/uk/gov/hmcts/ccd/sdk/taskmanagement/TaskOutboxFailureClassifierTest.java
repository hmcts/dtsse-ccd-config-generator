package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TaskOutboxFailureClassifierTest {

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {-1, 302, 408, 409, 425, 429, 500, 502, 503, 504})
  void treatsTransientAndAmbiguousStatusesAsRecoverable(Integer statusCode) {
    assertThat(TaskOutboxFailureClassifier.isRecoverable(statusCode)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 405, 406, 410, 415, 422})
  void treatsDeterministicClientFailuresAsUnrecoverable(int statusCode) {
    assertThat(TaskOutboxFailureClassifier.isRecoverable(statusCode)).isFalse();
  }
}
