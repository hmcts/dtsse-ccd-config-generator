package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReconfigureTaskOutboxPayload(String caseId, String caseType, List<TaskPayload> tasks) {
}

