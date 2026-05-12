package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class TaskOutboxRepositoryTest {

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void claimPendingUsesDatabaseClockForDueRows() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    TaskManagementProperties properties = new TaskManagementProperties();
    TaskOutboxRepository repository = new TaskOutboxRepository(jdbc, properties);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass((Class) Map.class);

    when(jdbc.query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class)))
        .thenReturn(List.of());

    repository.claimPending(5, 0);

    assertThat(sqlCaptor.getValue()).contains("o.next_attempt_at <= localtimestamp");
    assertThat(sqlCaptor.getValue())
        .contains("next_attempt_at = localtimestamp + (:processingTimeoutMillis * interval '1 millisecond')");
    assertThat(paramsCaptor.getValue()).doesNotContainKeys("now", "processingDeadline");
    assertThat(paramsCaptor.getValue()).containsEntry("processingTimeoutMillis", 300000L);
  }
}
