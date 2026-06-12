package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TaskOutboxRetryPolicyTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 12, 10, 0);

  @Test
  void defaultsToEightRetriesWithExponentialBackoff() {
    TaskOutboxRetryPolicy retryPolicy = new TaskOutboxRetryPolicy(new TaskManagementProperties());

    assertThat(retryPolicy.getMaxAttempts()).isEqualTo(9);
    assertThat(retryPolicy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusSeconds(1));
    assertThat(retryPolicy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plusSeconds(2));
    assertThat(retryPolicy.nextAttemptAt(8, NOW)).isEqualTo(NOW.plusSeconds(128));
    assertThat(retryPolicy.nextAttemptAt(9, NOW)).isNull();
  }
}
