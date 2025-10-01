package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.typetools.TypeResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;

@Slf4j
@Service
class BlobRepository {
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

  @SneakyThrows
  public List<DecentralisedCaseDetails> getCases(@RequestParam("case-refs") List<Long> caseRefs) {
    log.info("Fetching cases for references: {}", caseRefs);
    var params = Map.of("caseRefs", caseRefs);

    var results = ndb.queryForList(
        """
        select
              reference as id,
              -- Format timestamp in iso 8601
              to_json(c.created_date)#>>'{}' as created_date,
              jurisdiction,
              case_type_id,
              state,
              data::text as case_data,
              security_classification::text,
              version,
              to_json(last_state_modified_date)#>>'{}' as last_state_modified_date,
              to_json(coalesce(c.last_modified, c.created_date))#>>'{}' as last_modified,
              supplementary_data::text,
              case_revision
         from ccd.case_data c
         where reference IN (:caseRefs)
        """, params);

    return results.stream()
        .map(this::processCaseRow)
        .collect(Collectors.toList());
  }

  public DecentralisedCaseDetails getCase(Long caseRef) {
    return getCases(List.of(caseRef)).get(0);
  }

  @SneakyThrows
  public String serialiseDataFilteringExternalFields(CaseDetails caseDetails) {
    var o = defaultMapper.convertValue(caseDetails.getData(), caseDataType);
    return filteredMapper.writeValueAsString(o);
  }

  /**
   * Helper method to process a single row of case data from the database.
   * This centralizes the transformation logic for both single and bulk endpoints.
   */
  @SneakyThrows
  private DecentralisedCaseDetails processCaseRow(Map<String, Object> row) {
    var result = new HashMap<>(row);

    var data = defaultMapper.readValue((String) result.get("case_data"), caseDataType);
    result.put("case_data", caseRepository.getCase((Long) row.get("id"), (String) row.get("state"), data));

    var supplementaryDataJson = row.get("supplementary_data");
    result.put("supplementary_data", defaultMapper.readValue(supplementaryDataJson.toString(), Map.class));

    result.put("data_classification", Map.of());

    var revisionRaw = result.remove("case_revision");
    Long revision = null;
    if (revisionRaw instanceof Number number) {
      revision = number.longValue();
    }

    var caseDetails = defaultMapper.convertValue(
        result,
        uk.gov.hmcts.ccd.domain.model.definition.CaseDetails.class
    );
    if (revision != null) {
      caseDetails.setRevision(revision);
    }

    var response = new DecentralisedCaseDetails();
    response.setCaseDetails(caseDetails);
    response.setRevision(revision);
    return response;
  }

  private static final class DefaultCaseRepository implements CaseRepository<Map<String, Object>> {
    @Override
    public Map<String, Object> getCase(long caseRef, String state, Map<String, Object> data) {
      return data;
    }
  }
}
