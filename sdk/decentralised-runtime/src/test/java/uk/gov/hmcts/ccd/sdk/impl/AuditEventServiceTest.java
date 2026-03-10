package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;

class AuditEventServiceTest {

  private final NamedParameterJdbcTemplate ndb = mock(NamedParameterJdbcTemplate.class);
  private final AuditEventService service = new AuditEventService(
      ndb,
      new ObjectMapper(),
      Optional.empty(),
      mock(ResolvedConfigRegistry.class)
  );

  @Test
  void loadHistoryEventWhenMissingReturnsNotFound() {
    when(ndb.queryForObject(anyString(), anyMap(), any(RowMapper.class)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThatThrownBy(() -> service.loadHistoryEvent(1234567890123456L, 999L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          org.assertj.core.api.Assertions.assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
          org.assertj.core.api.Assertions.assertThat(rse.getReason()).isEqualTo("History event not found");
        });
  }
}
