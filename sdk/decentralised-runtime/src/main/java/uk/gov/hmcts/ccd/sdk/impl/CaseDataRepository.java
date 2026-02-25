package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;

@Slf4j
@Service
@RequiredArgsConstructor
class CaseDataRepository {
  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  private final NamedParameterJdbcTemplate ndb;
  private final ObjectMapper defaultMapper;

  public List<DecentralisedCaseDetails> getCases(List<Long> caseRefs) {
    log.info("Fetching cases for references: {}", caseRefs);
    var params = Map.of("caseRefs", caseRefs);

    return ndb.query(
        """
        select
              c.id,
              reference,
              c.created_date as created_date,
              jurisdiction,
              case_type_id,
              state,
              data::text as case_data,
              security_classification::text,
              version,
              last_state_modified_date,
              coalesce(c.last_modified, c.created_date) as last_modified,
              supplementary_data::text,
              case_revision
         from ccd.case_data c
         where reference IN (:caseRefs)
         order by reference asc
        """,
        params,
        (rs, rowNum) -> mapCaseDetails(rs)
    );
  }

  public DecentralisedCaseDetails getCase(Long caseRef) {
    var cases = getCases(List.of(caseRef));
    if (cases.isEmpty()) {
      throw new IllegalStateException("Case reference " + caseRef + " not found");
    }
    return cases.get(0);
  }

  DecentralisedCaseDetails caseDetailsAtEvent(long caseRef, long eventId) {
    var params = Map.of("caseRef", caseRef, "eventId", eventId);

    var results = ndb.query(
        """
        select
              cd.id,
              cd.reference,
              cd.created_date as created_date,
              cd.jurisdiction,
              ce.case_type_id,
              ce.state_id as state,
              ce.data::text as case_data,
              ce.security_classification::text,
              ce.version as version,
              ce.created_date as last_state_modified_date,
              ce.created_date as last_modified,
              cd.supplementary_data::text,
              ce.case_revision
         from ccd.case_event ce
         join ccd.case_data cd on cd.id = ce.case_data_id
        where cd.reference = :caseRef
          and ce.id = :eventId
        limit 1
        """,
        params,
        (rs, rowNum) -> mapCaseDetails(rs)
    );

    return results.stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No case event found for caseRef=" + caseRef + " eventId=" + eventId
        ));
  }

  @SneakyThrows
  long upsertCase(DecentralisedCaseEvent event, Optional<JsonNode> dataUpdate) {
    final String sql = """
        insert into ccd.case_data (
            last_modified,
            last_state_modified_date,
            jurisdiction,
            case_type_id,
            state,
            data,
            reference,
            security_classification,
            version,
            id
        )
        values (
            (now() at time zone 'UTC'),
            (now() at time zone 'UTC'),
            :jurisdiction,
            :case_type_id,
            :state,
            -- On INSERT: if no data was provided, start with {}
            case when :has_data then :data::jsonb else '{}'::jsonb end,
            :reference,
            :security_classification::ccd.securityclassification,
            -- Align with CCD's default for new rows
            coalesce(:version, 1),
            :id
        )
        on conflict (reference)
            do update set
                state = excluded.state,
                -- Update safety: never touch `data` unless explicitly provided
                data = case when :has_data then :data::jsonb else case_data.data end,
                security_classification = excluded.security_classification,
            last_modified = (now() at time zone 'UTC'),
            version = case
                        when
                          ((:has_data and case_data.data is distinct from excluded.data)
                          or case_data.state is distinct from excluded.state
                          or case_data.security_classification is distinct from excluded.security_classification)
                        then
                          case_data.version + 1
                        else
                          case_data.version
                      end,
            last_state_modified_date = case
                                         when case_data.state is distinct from excluded.state then
                                           (now() at time zone 'UTC')
                                         else case_data.last_state_modified_date
                                       end
            where case_data.version = excluded.version
            returning id;
        """;

    Map<String, Object> params = new HashMap<>();
    params.put("jurisdiction", event.getCaseDetails().getJurisdiction());
    params.put("case_type_id", event.getCaseDetails().getCaseTypeId());
    params.put("state", event.getCaseDetails().getState());
    params.put("data", dataUpdate.map(this::serialiseJsonNode).orElse(null));
    params.put("has_data", dataUpdate.isPresent());
    params.put("reference", event.getCaseDetails().getReference());
    params.put("security_classification", event.getCaseDetails().getSecurityClassification().toString());
    params.put("version", event.getCaseDetails().getVersion());
    params.put("id", event.getInternalCaseId());

    return ndb.queryForObject(sql, params, Long.class);
  }

  @SneakyThrows
  private String serialiseJsonNode(JsonNode node) {
    return defaultMapper.writeValueAsString(node);
  }

  @SneakyThrows
  private DecentralisedCaseDetails mapCaseDetails(ResultSet rs) throws SQLException {
    Long reference = rs.getObject("reference", Long.class);
    String state = rs.getString("state");

    var caseDetails = new CaseDetails();
    caseDetails.setReference(reference);
    caseDetails.setId(rs.getString("id"));
    caseDetails.setJurisdiction(rs.getString("jurisdiction"));
    caseDetails.setCaseTypeId(rs.getString("case_type_id"));
    caseDetails.setState(state);
    caseDetails.setVersion(rs.getObject("version", Integer.class));
    caseDetails.setCreatedDate(rs.getObject("created_date", LocalDateTime.class));
    caseDetails.setLastModified(rs.getObject("last_modified", LocalDateTime.class));
    caseDetails.setLastStateModifiedDate(rs.getObject("last_state_modified_date", LocalDateTime.class));

    var caseDataJson = rs.getString("case_data");
    caseDetails.setData(defaultMapper.readValue(caseDataJson, JSON_NODE_MAP));
    caseDetails.setDataClassification(Map.of());

    var supplementaryDataJson = rs.getString("supplementary_data");
    caseDetails.setSupplementaryData(defaultMapper.readValue(supplementaryDataJson, JSON_NODE_MAP));

    var securityClassification = rs.getString("security_classification");
    caseDetails.setSecurityClassification(SecurityClassification.valueOf(securityClassification));

    Long revision = rs.getObject("case_revision", Long.class);
    caseDetails.setRevision(revision);

    var response = new DecentralisedCaseDetails();
    response.setCaseDetails(caseDetails);
    response.setRevision(revision);
    return response;
  }
}
