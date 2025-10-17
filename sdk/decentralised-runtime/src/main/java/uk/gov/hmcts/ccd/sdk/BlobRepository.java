package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.typetools.TypeResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;

@Slf4j
@Service
class BlobRepository {
  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  private final NamedParameterJdbcTemplate ndb;
  private final CaseRepository caseRepository;
  private final Class<?> caseDataType;
  private final ObjectMapper defaultMapper;
  private final ObjectMapper filteredMapper;

  public BlobRepository(
      NamedParameterJdbcTemplate ndb,
      ObjectProvider<CaseRepository> caseRepositoryProvider,
      ObjectMapper defaultMapper
  ) {
    this.ndb = ndb;
    this.defaultMapper = defaultMapper;
    this.filteredMapper = defaultMapper.copy().setAnnotationIntrospector(new FilterExternalFieldsInspector());

    var resolved = caseRepositoryProvider.getIfAvailable();
    if (resolved == null) {
      this.caseRepository = new DefaultCaseRepository();
      this.caseDataType = Map.class;
    } else {
      this.caseRepository = resolved;
      Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CaseRepository.class, resolved.getClass());
      if (typeArgs.length == 0 || typeArgs[0] == null || typeArgs[0] == Object.class) {
        this.caseDataType = Map.class;
      } else {
        this.caseDataType = typeArgs[0];
      }
    }
  }

  public List<DecentralisedCaseDetails> getCases(@RequestParam("case-refs") List<Long> caseRefs) {
    log.info("Fetching cases for references: {}", caseRefs);
    var params = Map.of("caseRefs", caseRefs);

    return ndb.query(
        """
        select
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

  Optional<DecentralisedCaseDetails> caseDetailsAtEvent(long caseRef, long eventId) {
    var params = Map.of("caseRef", caseRef, "eventId", eventId);

    var results = ndb.query(
        """
        select
              cd.reference,
              cd.created_date as created_date,
              cd.jurisdiction,
              ce.case_type_id,
              ce.state_id as state,
              ce.data::text as case_data,
              cd.security_classification::text,
              cd.version,
              cd.last_state_modified_date,
              coalesce(cd.last_modified, cd.created_date) as last_modified,
              cd.supplementary_data::text,
              cd.case_revision
         from ccd.case_event ce
         join ccd.case_data cd on cd.id = ce.case_data_id
        where cd.reference = :caseRef
          and ce.id = :eventId
        limit 1
        """,
        params,
        (rs, rowNum) -> mapCaseDetails(rs)
    );

    return results.stream().findFirst();
  }

  @SneakyThrows
  public String serialiseDataFilteringExternalFields(CaseDetails caseDetails) {
    var o = defaultMapper.convertValue(caseDetails.getData(), caseDataType);
    return filteredMapper.writeValueAsString(o);
  }

  long upsertCase(DecentralisedCaseEvent event) {
    int version = Optional.ofNullable(event.getCaseDetails().getVersion()).orElse(1);
    String data = serialiseDataFilteringExternalFields(event.getCaseDetails());

    var sql = """
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
                (:data::jsonb),
                :reference,
                :security_classification::ccd.securityclassification,
                :version,
                :id
            )
            on conflict (reference)
            do update set
                state = excluded.state,
                data = excluded.data,
                security_classification = excluded.security_classification,
                last_modified = (now() at time zone 'UTC'),
                version = case
                            when
                              case_data.data is distinct from excluded.data
                              or case_data.state is distinct from excluded.state
                              or case_data.security_classification is distinct from excluded.security_classification
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

    var params = Map.of(
        "jurisdiction", event.getCaseDetails().getJurisdiction(),
        "case_type_id", event.getCaseDetails().getCaseTypeId(),
        "state", event.getCaseDetails().getState(),
        "data", data,
        "reference", event.getCaseDetails().getReference(),
        "security_classification", event.getCaseDetails().getSecurityClassification().toString(),
        "version", version,
        "id", event.getInternalCaseId()
    );

    return ndb.queryForObject(sql, params, Long.class);
  }

  private DecentralisedCaseDetails mapCaseDetails(ResultSet rs) throws SQLException {
    try {
      Long reference = rs.getObject("reference", Long.class);
      String state = rs.getString("state");

      var caseDetails = new CaseDetails();
      caseDetails.setReference(reference);
      caseDetails.setId(String.valueOf(reference));
      caseDetails.setJurisdiction(rs.getString("jurisdiction"));
      caseDetails.setCaseTypeId(rs.getString("case_type_id"));
      caseDetails.setState(state);
      caseDetails.setVersion(rs.getObject("version", Integer.class));
      caseDetails.setCreatedDate(rs.getObject("created_date", LocalDateTime.class));
      caseDetails.setLastModified(rs.getObject("last_modified", LocalDateTime.class));
      caseDetails.setLastStateModifiedDate(rs.getObject("last_state_modified_date", LocalDateTime.class));

      var caseDataJson = rs.getString("case_data");
      var rawCaseData = defaultMapper.readValue(caseDataJson, caseDataType);
      var projectedCaseData = caseRepository.getCase(reference, state, rawCaseData);
      Map<String, JsonNode> caseData = defaultMapper.convertValue(projectedCaseData, JSON_NODE_MAP);
      caseDetails.setData(caseData);
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
    } catch (Exception exception) {
      throw new SQLException("Failed to map case data", exception);
    }
  }

  private static final class DefaultCaseRepository implements CaseRepository<Map<String, Object>> {
    @Override
    public Map<String, Object> getCase(long caseRef, String state, Map<String, Object> data) {
      return data;
    }
  }

}
