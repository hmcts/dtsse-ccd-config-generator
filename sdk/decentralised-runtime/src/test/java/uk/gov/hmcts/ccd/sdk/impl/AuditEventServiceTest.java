package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.reform.ccd.client.model.SignificantItem;

class AuditEventServiceTest {

  private final NamedParameterJdbcTemplate ndb = mock(NamedParameterJdbcTemplate.class);
  private final AuditEventService service = new AuditEventService(
      ndb,
      new ObjectMapper(),
      Optional.empty(),
      mock(ResolvedConfigRegistry.class)
  );

  @Test
  void reservesCaseEventIdAndSetsAuditContextInOneQuery() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(ndb.getJdbcTemplate()).thenReturn(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(123L);

    assertThat(service.reserveCaseEventId()).isEqualTo(123L);

    verify(jdbc).queryForObject(
        argThat(sql -> sql.contains("set_config(")
            && sql.contains("nextval('ccd.case_event_id_seq')::text")),
        eq(Long.class)
    );
    verifyNoMoreInteractions(jdbc);
  }

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

  @Test
  void saveAuditRecordRejectsInvalidSignificantItemUrlBeforePersistingEvent() {
    var significantItem = SignificantItem.builder()
        .type("DOCUMENT")
        .description("Generated document")
        .url("not a url")
        .build();

    assertThatThrownBy(() -> service.saveAuditRecord(
        123L,
        null,
        null,
        null,
        UUID.randomUUID(),
        Optional.of(significantItem)
    ))
        .isInstanceOf(CallbackValidationException.class)
        .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(
            ((CallbackValidationException) ex).getErrors()
        ).containsExactly("Significant item URL is not valid"));

    verifyNoInteractions(ndb);
  }
}
