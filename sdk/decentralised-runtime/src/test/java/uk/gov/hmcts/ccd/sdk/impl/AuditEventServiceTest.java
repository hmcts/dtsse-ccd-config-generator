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
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.reform.ccd.client.model.SignificantItem;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

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

  @Test
  void saveSystemAuditRecordDoesNotPublishTheUnregisteredEvent() throws Exception {
    MessagePublisher publisher = mock(MessagePublisher.class);
    ResolvedConfigRegistry registry = mock(ResolvedConfigRegistry.class);
    final var systemAuditService = new AuditEventService(
        ndb,
        new ObjectMapper(),
        Optional.of(publisher),
        registry
    );
    when(registry.labelForState("TestCase", "SUBMITTED")).thenReturn(Optional.of("Submitted"));
    when(ndb.queryForObject(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> {
          ResultSet resultSet = mock(ResultSet.class);
          when(resultSet.getLong("id")).thenReturn(42L);
          when(resultSet.getTimestamp("created_date"))
              .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 8, 6, 12, 0)));
          return invocation.<RowMapper<?>>getArgument(2).mapRow(resultSet, 0);
        });

    var currentCase = new uk.gov.hmcts.ccd.domain.model.definition.CaseDetails();
    currentCase.setReference(1234567890123456L);
    currentCase.setState("SUBMITTED");
    currentCase.setData(Map.of("field", new ObjectMapper().getNodeFactory().textNode("value")));
    currentCase.setSecurityClassification(SecurityClassification.PUBLIC);
    currentCase.setVersion(1);
    currentCase.setRevision(1L);

    var event = DecentralisedCaseEvent.builder()
        .internalCaseId(123L)
        .caseDetails(currentCase)
        .eventDetails(DecentralisedEventDetails.builder()
            .caseType("TestCase")
            .eventId("systemEvent")
            .eventName("System event")
            .build())
        .build();
    var systemUser = new IdamService.User(
        null,
        new UserInfo("system:pcs-api", "system:pcs-api", "system:pcs-api", "System", "pcs-api", List.of())
    );

    long eventId = systemAuditService.saveSystemAuditRecord(
        42L,
        event,
        systemUser,
        currentCase,
        UUID.randomUUID()
    );

    org.assertj.core.api.Assertions.assertThat(eventId).isEqualTo(42L);
    verifyNoInteractions(publisher);
  }
}
