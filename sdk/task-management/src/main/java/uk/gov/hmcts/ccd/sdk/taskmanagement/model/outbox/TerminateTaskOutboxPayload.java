package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TerminateTaskOutboxPayload(String caseId, String caseType, List<String> taskTypes) {
}
