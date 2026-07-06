package uk.gov.hmcts.ccd.sdk.retention.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CcdCaseDataExistenceRequest(
    @JsonProperty("jurisdiction")
    String jurisdiction,
    @JsonProperty("case_references")
    List<String> caseReferences
) {
}
