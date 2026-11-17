package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseProjectionServiceTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void projectsTheSameSearchCriteriaIdsEveryTime() throws Exception {
    // As data store's processor leaves it: a new random id on every item, each time it runs.
    var first = CaseProjectionService.stabiliseSearchCriteriaIds(1234L, searchCriteria());
    var second = CaseProjectionService.stabiliseSearchCriteriaIds(1234L, searchCriteria());

    assertThat(first).isEqualTo(second);
    assertThat(ids(first, "SearchParties")).doesNotHaveDuplicates().hasSize(2);
    assertThat(ids(first, "OtherCaseReferences")).hasSize(1);
  }

  @Test
  void givesAnotherCaseOrAnotherItemAnotherId() throws Exception {
    var one = CaseProjectionService.stabiliseSearchCriteriaIds(1234L, searchCriteria());
    var other = CaseProjectionService.stabiliseSearchCriteriaIds(5678L, searchCriteria());

    assertThat(ids(one, "SearchParties")).doesNotContainAnyElementsOf(ids(other, "SearchParties"));
  }

  @Test
  void leavesDataWithoutSearchCriteriaAlone() throws Exception {
    Map<String, JsonNode> data = Map.of("applicant", mapper.readTree("{\"name\": \"Joe\"}"));

    assertThat(CaseProjectionService.stabiliseSearchCriteriaIds(1234L, data)).isEqualTo(data);
  }

  private Map<String, JsonNode> searchCriteria() throws Exception {
    return mapper.readValue("""
        {"SearchCriteria": {
          "OtherCaseReferences": [{"id": "%s", "value": "1111222233334444"}],
          "SearchParties": [
            {"id": "%s", "value": {"Name": "Joe Bloggs"}},
            {"id": "%s", "value": {"Name": "Jane Doe"}}
          ]
        }}
        """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), new TypeReference<>() {});
  }

  private static java.util.List<String> ids(Map<String, JsonNode> data, String collection) {
    var ids = new java.util.ArrayList<String>();
    data.get("SearchCriteria").get(collection).forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }
}
