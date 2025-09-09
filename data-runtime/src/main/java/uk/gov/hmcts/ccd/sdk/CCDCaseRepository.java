package uk.gov.hmcts.ccd.sdk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.typetools.TypeResolver;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;

@Slf4j
@Service
public class CCDCaseRepository {
    private final NamedParameterJdbcTemplate ndb;
    private final CaseRepository caseRepository;
    private final Class caseDataType;
    private final ObjectMapper defaultMapper;
    private final ObjectMapper filteredMapper;

    public CCDCaseRepository(NamedParameterJdbcTemplate ndb,
        CaseRepository caseRepository,
                             ObjectMapper defaultMapper) {
        this.ndb = ndb;
        this.caseRepository = caseRepository;
        this.defaultMapper = defaultMapper;
        this.filteredMapper = defaultMapper.copy().setAnnotationIntrospector(new FilterExternalFieldsInspector());
        Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CaseRepository.class, caseRepository.getClass());
        this.caseDataType = typeArgs[0];
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
                  coalesce(most_recent_event.id, 2) as global_version
             from ccd.case_data c
                 left join lateral (
                   select ce.id from ccd.case_event ce where ce.case_data_id = c.id
                   order by ce.id DESC limit 1
                 ) as most_recent_event on true
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
        result.put("case_data", caseRepository.getCase((Long) row.get("id"), data));

        var supplementaryDataJson = row.get("supplementary_data");
        result.put("supplementary_data", defaultMapper.readValue(supplementaryDataJson.toString(), Map.class));

        result.put("data_classification", Map.of());

        var response = new DecentralisedCaseDetails();
        response.setVersion((Long) result.remove("global_version"));
        response.setCaseDetails(defaultMapper.convertValue(result, uk.gov.hmcts.ccd.domain.model.definition.CaseDetails.class));
        return response;
    }

}
