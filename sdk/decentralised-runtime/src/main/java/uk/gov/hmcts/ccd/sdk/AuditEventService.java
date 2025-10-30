package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedAuditEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.domain.model.std.AuditEvent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

@Slf4j
@RequiredArgsConstructor
@Service
class AuditEventService {

  private final NamedParameterJdbcTemplate ndb;
  private final ObjectMapper defaultMapper;
  private final Optional<MessagePublisher> publisher;
  private final ResolvedConfigRegistry registry;

  public List<DecentralisedAuditEvent> loadHistory(long caseRef) {
    final String sql = """
        select ce.*,
               cd.reference as "case_reference"
        from ccd.case_event ce
             join ccd.case_data cd on cd.id = ce.case_data_id
        where cd.reference = :caseRef
        order by id desc
        """;

    return ndb.query(sql, Map.of("caseRef", caseRef), this::mapAuditEvent);
  }

  public DecentralisedAuditEvent loadHistoryEvent(long caseRef, long eventId) {
    final String sql = """
        select ce.*,
               cd.reference as "case_reference"
        from ccd.case_event ce
             join ccd.case_data cd on cd.id = ce.case_data_id
        where cd.reference = :caseRef and ce.id = :eventId
        """;

    return ndb.queryForObject(
        sql,
        Map.of("caseRef", caseRef, "eventId", eventId),
        this::mapAuditEvent
    );
  }

  @SneakyThrows
  public long saveAuditRecord(
      DecentralisedCaseEvent event,
      IdamService.User user,
      uk.gov.hmcts.ccd.domain.model.definition.CaseDetails currentView,
      UUID idempotencyKey
  ) {
    final String oldState = event.getCaseDetailsBefore() != null
        ? event.getCaseDetailsBefore().getState()
        : null;
    var eventDetails = event.getEventDetails();
    var sql = """
        insert into ccd.case_event (
          data,
          event_id,
          user_id,
          case_data_id,
          case_type_id,
          case_type_version,
          state_id,
          user_first_name,
          user_last_name,
          event_name,
          state_name,
          summary,
          description,
          security_classification,
          idempotency_key)
        values (
          :data::jsonb,
          :event_id,
          :user_id,
          :case_data_id,
          :case_type_id,
          :case_type_version,
          :state_id,
          :user_first_name,
          :user_last_name,
          :event_name,
          :state_name,
          :summary,
          :description,
          :security_classification::ccd.securityclassification,
          :idempotency_key
        )
        returning id, created_date
        """;

    var stateName = registry.labelForState(
            eventDetails.getCaseType(),
            String.valueOf(currentView.getState())
        )
        .orElse(String.valueOf(currentView.getState()));

    var params = new MapSqlParameterSource()
        .addValue("data", defaultMapper.writeValueAsString(currentView.getData()))
        .addValue("event_id", eventDetails.getEventId())
        .addValue("user_id", user.getUserDetails().getUid())
        .addValue("case_data_id", event.getInternalCaseId())
        .addValue("case_type_id", eventDetails.getCaseType())
        .addValue("case_type_version", 1)
        .addValue("state_id", currentView.getState())
        .addValue("user_first_name", user.getUserDetails().getGivenName())
        .addValue("user_last_name", user.getUserDetails().getFamilyName())
        .addValue("event_name", eventDetails.getEventName())
        .addValue("state_name", stateName)
        .addValue("summary", eventDetails.getSummary())
        .addValue("description", eventDetails.getDescription())
        .addValue("security_classification", currentView.getSecurityClassification().toString())
        .addValue("idempotency_key", idempotencyKey);

    var inserted = ndb.queryForObject(sql, params, this::mapInsertedAuditEvent);

    if (this.publisher.isPresent()) {
      log.info(
          "Publishing event {} for case reference: {}",
          eventDetails.getEventId(),
          currentView.getReference()
      );
      this.publisher.get().publishEvent(
          currentView.getReference(),
          user.getUserDetails().getUid(),
          eventDetails.getEventId(),
          oldState,
          toCaseDetails(event.getCaseDetails()),
          inserted.id(),
          inserted.createdDate()
      );
    } else {
      log.info(
          "Message publishing disabled, skipping event publication for case reference: {}",
          currentView.getReference()
      );
    }
    return inserted.id();
  }

  private CaseDetails toCaseDetails(uk.gov.hmcts.ccd.domain.model.definition.CaseDetails data) {
    return defaultMapper.convertValue(data, CaseDetails.class);
  }

  @SneakyThrows
  private DecentralisedAuditEvent mapAuditEvent(ResultSet rs, int rowNum) {
    var auditEvent = new AuditEvent();
    auditEvent.setId(rs.getLong("id"));
    auditEvent.setEventId(rs.getString("event_id"));
    auditEvent.setEventName(rs.getString("event_name"));
    auditEvent.setSummary(rs.getString("summary"));
    auditEvent.setDescription(rs.getString("description"));
    auditEvent.setUserId(rs.getString("user_id"));
    auditEvent.setUserFirstName(rs.getString("user_first_name"));
    auditEvent.setUserLastName(rs.getString("user_last_name"));
    auditEvent.setCaseTypeId(rs.getString("case_type_id"));
    auditEvent.setCaseTypeVersion(rs.getInt("case_type_version"));
    auditEvent.setStateId(rs.getString("state_id"));
    auditEvent.setStateName(rs.getString("state_name"));
    auditEvent.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
    auditEvent.setProxiedBy(rs.getString("proxied_by"));
    auditEvent.setProxiedByFirstName(rs.getString("proxied_by_first_name"));
    auditEvent.setProxiedByLastName(rs.getString("proxied_by_last_name"));
    auditEvent.setSecurityClassification(SecurityClassification.valueOf(rs.getString("security_classification")));

    auditEvent.setData(defaultMapper.readValue(rs.getString("data"), DATA_TYPE));
    auditEvent.setDataClassification(Map.of());

    var decentralisedEvent = new DecentralisedAuditEvent();
    decentralisedEvent.setId(rs.getLong("id"));
    decentralisedEvent.setCaseReference(rs.getLong("case_reference"));
    decentralisedEvent.setEvent(auditEvent);
    return decentralisedEvent;
  }

  private InsertedAuditEvent mapInsertedAuditEvent(ResultSet rs, int rowNum) throws SQLException {
    Timestamp created = rs.getTimestamp("created_date");
    LocalDateTime createdDate = created.toLocalDateTime();
    return new InsertedAuditEvent(rs.getLong("id"), createdDate);
  }

  private record InsertedAuditEvent(long id, LocalDateTime createdDate) {}

  private static final TypeReference<Map<String, JsonNode>> DATA_TYPE = new TypeReference<>() {};
}
