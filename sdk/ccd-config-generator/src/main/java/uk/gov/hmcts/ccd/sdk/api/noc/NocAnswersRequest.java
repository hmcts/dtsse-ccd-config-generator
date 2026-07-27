package uk.gov.hmcts.ccd.sdk.api.noc;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record NocAnswersRequest(
    @JsonProperty("case_id") long caseId,
    List<NocAnswer> answers
) {
}
