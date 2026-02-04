package uk.gov.hmcts.ccd.sdk.taskmanagement.model.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;
import lombok.Builder;

@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskReconfigureRequest(String caseId, List<TaskPayload> tasks) {
}

