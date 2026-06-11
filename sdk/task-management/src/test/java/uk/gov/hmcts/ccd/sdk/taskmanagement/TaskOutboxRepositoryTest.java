package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxStatus;

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

    assertThat(sqlCaptor.getValue()).contains("o.next_attempt_at <= (current_timestamp at time zone 'UTC')");
    assertThat(sqlCaptor.getValue()).contains("next_attempt_at =");
    assertThat(sqlCaptor.getValue()).contains("(current_timestamp at time zone 'UTC')");
    assertThat(sqlCaptor.getValue()).contains(":processingTimeoutMillis * interval '1 millisecond'");
    assertThat(paramsCaptor.getValue()).doesNotContainKeys("now", "processingDeadline");
    assertThat(paramsCaptor.getValue()).containsEntry("processingTimeoutMillis", 300000L);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void claimPendingOrdersRowsByTriggerAndActionPriority() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    TaskOutboxRepository repository = new TaskOutboxRepository(jdbc, new TaskManagementProperties());
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

    when(jdbc.query(sqlCaptor.capture(), any(Map.class), any(RowMapper.class))).thenReturn(List.of());

    repository.claimPending(5, 0);

    assertThat(sqlCaptor.getValue()).contains("partition by o.case_id, o.event_id, o.created");
    assertThat(sqlCaptor.getValue()).contains("predecessor.action_priority < o.action_priority");
    assertThat(sqlCaptor.getValue()).contains("predecessor.status::text <> :processedStatus");
    assertThat(sqlCaptor.getValue()).contains("prior.status::text in (:newStatus, :processingStatus)");
    assertThat(sqlCaptor.getValue()).doesNotContain("o.event_id is null", "o.event_id is not null");
  }

  @Test
  void enqueueStoresWaitingStatusForDelayedRows() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    TaskOutboxRepository repository = new TaskOutboxRepository(jdbc, new TaskManagementProperties());
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
    LocalDateTime nextAttemptAt = LocalDateTime.now().plusHours(1);

    when(jdbc.queryForObject(anyString(), paramsCaptor.capture(), eq(Long.class))).thenReturn(42L);

    long id = repository.enqueueAndReturnId(
        "1234567890123456",
        "event-id",
        LocalDateTime.now(),
        "{}",
        "initiate",
        nextAttemptAt
    );

    assertThat(id).isEqualTo(42L);
    assertThat(paramsCaptor.getValue().getValue("status")).isEqualTo(TaskOutboxStatus.WAITING.name());
    assertThat(paramsCaptor.getValue().getValue("nextAttemptAt")).isEqualTo(nextAttemptAt);
  }

  @Test
  void enqueueStoresNewStatusForImmediateRows() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    TaskOutboxRepository repository = new TaskOutboxRepository(jdbc, new TaskManagementProperties());
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

    when(jdbc.queryForObject(anyString(), paramsCaptor.capture(), eq(Long.class))).thenReturn(42L);

    long id = repository.enqueueAndReturnId(
        "1234567890123456",
        "event-id",
        LocalDateTime.now(),
        "{}",
        "initiate",
        null
    );

    assertThat(id).isEqualTo(42L);
    assertThat(paramsCaptor.getValue().getValue("status")).isEqualTo(TaskOutboxStatus.NEW.name());
    assertThat(paramsCaptor.getValue().getValue("nextAttemptAt")).isNull();
  }

  @Test
  void enqueueStoresTriggerMetadataAndSharedCreatedTimestamp() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    TaskOutboxRepository repository = new TaskOutboxRepository(jdbc, new TaskManagementProperties());
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
    LocalDateTime created = LocalDateTime.of(2026, 6, 11, 12, 0);

    when(jdbc.queryForObject(anyString(), paramsCaptor.capture(), eq(Long.class))).thenReturn(42L);

    repository.enqueue("1234567890123456", "event-id", created, "{}", "cancel");

    assertThat(paramsCaptor.getValue().getValue("eventId")).isEqualTo("event-id");
    assertThat(paramsCaptor.getValue().getValue("created")).isEqualTo(created);
  }
}
