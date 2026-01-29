package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CancelTaskOutboxPayload(String caseId, String caseType, List<String> processCategoryIdentifiers) {
}
