package uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReconfigureTaskOutboxPayload(String caseId, String caseType, List<TaskPayload> tasks) {
}

