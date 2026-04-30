package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class TaskOutboxRepositoryTest {

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void claimPendingUsesUtcClockForDueRows() {
    TimeZone defaultTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
    try {
      NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
      TaskManagementProperties properties = new TaskManagementProperties();
      TaskOutboxRepository repository = new TaskOutboxRepository(jdbc, properties);
      ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass((Class) Map.class);

      when(jdbc.query(anyString(), paramsCaptor.capture(), any(RowMapper.class)))
          .thenReturn(List.of());

      LocalDateTime beforeUtc = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
      repository.claimPending(5, 0);
      LocalDateTime afterUtc = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

      LocalDateTime capturedNow = (LocalDateTime) paramsCaptor.getValue().get("now");

      assertThat(capturedNow).isBetween(beforeUtc, afterUtc);
      assertThat(capturedNow).isBefore(LocalDateTime.now().minusHours(1));
    } finally {
      TimeZone.setDefault(defaultTimeZone);
    }
  }
}
