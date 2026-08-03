package uk.gov.hmcts.ccd.sdk.api.noc;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NocAnswer(
    @JsonProperty("question_id") String questionId,
    String value
) {
}
