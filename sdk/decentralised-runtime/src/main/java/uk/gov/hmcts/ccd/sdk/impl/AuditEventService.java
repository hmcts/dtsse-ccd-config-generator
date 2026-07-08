package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedAuditEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.domain.model.callbacks.SignificantItem;
import uk.gov.hmcts.ccd.domain.model.std.AuditEvent;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

@Slf4j
@RequiredArgsConstructor
@Service(value = "uk.gov.hmcts.ccd.sdk.impl.AuditEventService")
class AuditEventService {

  private final NamedParameterJdbcTemplate ndb;
  private final ObjectMapper defaultMapper;
  private final Optional<MessagePublisher> publisher;
  private final ResolvedConfigRegistry registry;

  public List<DecentralisedAuditEvent> loadHistory(long caseRef) {
    final String sql = """
        select ce.*,
               cd.reference as "case_reference",
               significant_item.description as significant_item_description,
               significant_item."type"::text as significant_item_type,
               significant_item.url as significant_item_url
        from ccd.case_event ce
             join ccd.case_data cd on cd.id = ce.case_data_id
             left join lateral (
               select item.description, item."type", item.url
               from ccd.case_event_significant_items item
               where item.case_event_id = ce.id
               order by item.id desc
               limit 1
             ) significant_item on true
        where cd.reference = :caseRef
        order by id desc
        """;

    return ndb.query(sql, Map.of("caseRef", caseRef), this::mapAuditEvent);
  }

  public DecentralisedAuditEvent loadHistoryEvent(long caseRef, long eventId) {
    final String sql = """
        select ce.*,
               cd.reference as "case_reference",
               significant_item.description as significant_item_description,
               significant_item."type"::text as significant_item_type,
               significant_item.url as significant_item_url
        from ccd.case_event ce
             join ccd.case_data cd on cd.id = ce.case_data_id
             left join lateral (
               select item.description, item."type", item.url
               from ccd.case_event_significant_items item
               where item.case_event_id = ce.id
               order by item.id desc
               limit 1
             ) significant_item on true
        where cd.reference = :caseRef and ce.id = :eventId
        """;

    try {
      return ndb.queryForObject(
          sql,
          Map.of("caseRef", caseRef, "eventId", eventId),
          this::mapAuditEvent
      );
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "History event not found");
    }
  }

  @SneakyThrows
  public long saveAuditRecord(
      DecentralisedCaseEvent event,
      IdamService.User user,
      uk.gov.hmcts.ccd.domain.model.definition.CaseDetails currentView,
      UUID idempotencyKey,
      Optional<uk.gov.hmcts.reform.ccd.client.model.SignificantItem> significantItem
  ) {
    significantItem.ifPresent(this::validateSignificantItem);

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
          version,
          case_revision,
          idempotency_key,
          proxied_by,
          proxied_by_first_name,
          proxied_by_last_name)
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
          :version,
          :case_revision,
          :idempotency_key,
          :proxied_by,
          :proxied_by_first_name,
          :proxied_by_last_name
        )
        returning id, created_date
        """;

    var stateName = registry.labelForState(
            eventDetails.getCaseType(),
            String.valueOf(currentView.getState())
        )
        .orElse(String.valueOf(currentView.getState()));

    var auditUserId = user.userDetails().getUid();
    var auditUserFirstName = user.userDetails().getGivenName();
    var auditUserLastName = user.userDetails().getFamilyName();
    String proxiedBy = null;
    String proxiedByFirstName = null;
    String proxiedByLastName = null;

    if (eventDetails.getProxiedBy() != null && !eventDetails.getProxiedBy().isBlank()) {
      auditUserId = eventDetails.getProxiedBy();
      auditUserFirstName = eventDetails.getProxiedByFirstName();
      auditUserLastName = eventDetails.getProxiedByLastName();
      proxiedBy = user.userDetails().getUid();
      proxiedByFirstName = user.userDetails().getGivenName();
      proxiedByLastName = user.userDetails().getFamilyName();
    }

    var params = new MapSqlParameterSource()
        .addValue("data", defaultMapper.writeValueAsString(currentView.getData()))
        .addValue("event_id", eventDetails.getEventId())
        .addValue("user_id", auditUserId)
        .addValue("case_data_id", event.getInternalCaseId())
        .addValue("case_type_id", eventDetails.getCaseType())
        .addValue("case_type_version", 1)
        .addValue("state_id", currentView.getState())
        .addValue("user_first_name", auditUserFirstName)
        .addValue("user_last_name", auditUserLastName)
        .addValue("event_name", eventDetails.getEventName())
        .addValue("state_name", stateName)
        .addValue("summary", eventDetails.getSummary())
        .addValue("description", eventDetails.getDescription())
        .addValue("security_classification", currentView.getSecurityClassification().toString())
        .addValue("version", currentView.getVersion())
        .addValue("case_revision", currentView.getRevision())
        .addValue("idempotency_key", idempotencyKey)
        .addValue("proxied_by", proxiedBy)
        .addValue("proxied_by_first_name", proxiedByFirstName)
        .addValue("proxied_by_last_name", proxiedByLastName);

    var inserted = ndb.queryForObject(sql, params, this::mapInsertedAuditEvent);
    significantItem.ifPresent(item -> saveSignificantItem(inserted.id(), item));

    if (this.publisher.isPresent()) {
      log.info(
          "Publishing event {} for case reference: {}",
          eventDetails.getEventId(),
          currentView.getReference()
      );
      this.publisher.get().publishEvent(
          currentView.getReference(),
          user.userDetails().getUid(),
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

  private void validateSignificantItem(uk.gov.hmcts.reform.ccd.client.model.SignificantItem item) {
    if (item.getType() == null || item.getType().isBlank()
        || item.getDescription() == null || item.getDescription().isBlank()
        || item.getUrl() == null || item.getUrl().isBlank()) {
      return;
    }

    try {
      new URI(item.getUrl()).toURL();
    } catch (IllegalArgumentException | MalformedURLException | URISyntaxException e) {
      throw new CallbackValidationException(
          List.of("Significant item URL is not valid"),
          Collections.emptyList()
      );
    }
  }

  private void saveSignificantItem(long caseEventId, uk.gov.hmcts.reform.ccd.client.model.SignificantItem item) {
    if (item.getType() == null || item.getType().isBlank()
        || item.getDescription() == null || item.getDescription().isBlank()) {
      return;
    }

    ndb.update(
        """
        insert into ccd.case_event_significant_items (
          description,
          "type",
          url,
          case_event_id
        ) values (
          :description,
          :type::ccd.significant_item_type,
          :url,
          :caseEventId
        )
        """,
        new MapSqlParameterSource()
            .addValue("description", item.getDescription())
            .addValue("type", item.getType())
            .addValue("url", item.getUrl())
            .addValue("caseEventId", caseEventId)
    );
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
    auditEvent.setSignificantItem(mapSignificantItem(rs));

    auditEvent.setData(defaultMapper.readValue(rs.getString("data"), DATA_TYPE));
    auditEvent.setDataClassification(Map.of());

    var decentralisedEvent = new DecentralisedAuditEvent();
    decentralisedEvent.setId(rs.getLong("id"));
    decentralisedEvent.setCaseReference(rs.getLong("case_reference"));
    decentralisedEvent.setEvent(auditEvent);
    return decentralisedEvent;
  }

  private SignificantItem mapSignificantItem(ResultSet rs) throws SQLException {
    String type = rs.getString("significant_item_type");
    if (type == null) {
      return null;
    }

    var item = new SignificantItem();
    item.setType(type);
    item.setDescription(rs.getString("significant_item_description"));
    item.setUrl(rs.getString("significant_item_url"));
    return item;
  }

  private InsertedAuditEvent mapInsertedAuditEvent(ResultSet rs, int rowNum) throws SQLException {
    Timestamp created = rs.getTimestamp("created_date");
    LocalDateTime createdDate = created.toLocalDateTime();
    return new InsertedAuditEvent(rs.getLong("id"), createdDate);
  }

  private record InsertedAuditEvent(long id, LocalDateTime createdDate) {}

  private static final TypeReference<Map<String, JsonNode>> DATA_TYPE = new TypeReference<>() {};
}
