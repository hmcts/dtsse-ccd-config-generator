package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedAuditEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.domain.model.std.AuditEvent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class CaseEventHistoryService {

    private final NamedParameterJdbcTemplate ndb;
    private final ObjectMapper defaultMapper;
    private final Optional<MessagePublisher> publisher;
    private final CCDEventListener eventListener;

    public List<DecentralisedAuditEvent> loadHistory(long caseRef) {
        final String sql = """
                select ce.*,
                   cd.reference as "case_reference"
                from ccd.case_event ce
                     join ccd.case_data cd on cd.id = ce.case_data_id
                where cd.reference = :caseRef
                order by id desc
                """;

        return ndb.query(sql, Map.of("caseRef", caseRef), new DecentralisedAuditEventRowMapper());
    }

    public DecentralisedAuditEvent loadHistoryEvent(long caseRef, long eventId) {
        final String sql = """
            select ce.*,
                   cd.reference as "case_reference"
            from ccd.case_event ce
                 join ccd.case_data cd on cd.id = ce.case_data_id
              where cd.reference = :caseRef and ce.id = :eventId
           """;

        return ndb.queryForObject(sql, Map.of("caseRef", caseRef, "eventId", eventId),
            new DecentralisedAuditEventRowMapper());
    }

    @SneakyThrows
    public long saveAuditRecord(DecentralisedCaseEvent event, IdamService.User user, uk.gov.hmcts.ccd.domain.model.definition.CaseDetails currentView, long caseDataId) {
        var oldState = event.getCaseDetailsBefore() != null
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
                  security_classification)
                values (:data::jsonb, :event_id, :user_id, :case_data_id, :case_type_id, :case_type_version, :state_id, :user_first_name, :user_last_name, :event_name, :state_name, :summary, :description, :security_classification::ccd.securityclassification)
                returning id, created_date
                """;

        var params = new HashMap<String, Object>();
        params.put("data", defaultMapper.writeValueAsString(currentView.getData()));
        params.put("event_id", eventDetails.getEventId());
        params.put("user_id", user.getUserDetails().getUid());
        params.put("case_data_id", caseDataId);
        params.put("case_type_id", eventDetails.getCaseType());
        params.put("case_type_version", 1);
        params.put("state_id", currentView.getState());
        params.put("user_first_name", user.getUserDetails().getGivenName());
        params.put("user_last_name", user.getUserDetails().getFamilyName());
        params.put("event_name", eventDetails.getEventName());
        params.put("state_name", eventListener.nameForState(eventDetails.getCaseType(), String.valueOf(currentView.getState())));
        params.put("summary", eventDetails.getSummary());
        params.put("description", eventDetails.getDescription());
        params.put("security_classification", currentView.getSecurityClassification().toString());

        var result = ndb.queryForMap(sql, params);
        var eventId = (long) result.get("id");
        var timestamp = ((java.sql.Timestamp) result.get("created_date")).toLocalDateTime();
        if (this.publisher.isPresent()) {
            log.info("Publishing event {} for case reference: {}", eventDetails.getEventId(), currentView.getReference());
            this.publisher.get().publishEvent(
                currentView.getReference(),
                user.getUserDetails().getUid(),
                eventDetails.getEventId(),
                oldState,
                toCaseDetails(event.getCaseDetails()),
                eventId,
                timestamp
            );
        } else {
            log.info("Message publishing disabled, skipping event publication for case reference: {}", currentView.getReference());
        }
        return eventId;
    }

    private CaseDetails toCaseDetails(uk.gov.hmcts.ccd.domain.model.definition.CaseDetails data) {
        return defaultMapper.convertValue(data, CaseDetails.class);
    }

    private class DecentralisedAuditEventRowMapper implements RowMapper<DecentralisedAuditEvent> {

        @SneakyThrows
        @Override
        public DecentralisedAuditEvent mapRow(ResultSet rs, int rowNum) {
            DecentralisedAuditEvent decentralisedAuditEvent = new DecentralisedAuditEvent();
            decentralisedAuditEvent.setId(rs.getLong("id"));
            decentralisedAuditEvent.setCaseReference(rs.getLong("case_reference"));

            AuditEvent event = new AuditEvent();
            event.setId(rs.getLong("id"));
            event.setEventId(rs.getString("event_id"));
            event.setEventName(rs.getString("event_name"));
            event.setSummary(rs.getString("summary"));
            event.setDescription(rs.getString("description"));
            event.setUserId(rs.getString("user_id"));
            event.setUserFirstName(rs.getString("user_first_name"));
            event.setUserLastName(rs.getString("user_last_name"));
            event.setCaseTypeId(rs.getString("case_type_id"));
            event.setCaseTypeVersion(rs.getInt("case_type_version"));
            event.setStateId(rs.getString("state_id"));
            event.setStateName(rs.getString("state_name"));
            event.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
            event.setProxiedBy(rs.getString("proxied_by"));
            event.setProxiedByFirstName(rs.getString("proxied_by_first_name"));
            event.setProxiedByLastName(rs.getString("proxied_by_last_name"));
            event.setSecurityClassification(SecurityClassification.valueOf(rs.getString("security_classification")));

            String dataJson = rs.getString("data");
            event.setData(defaultMapper.readValue(dataJson, new TypeReference<>() {}));

            event.setDataClassification(Map.of());

            decentralisedAuditEvent.setEvent(event);
            return decentralisedAuditEvent;
        }
    }
}
