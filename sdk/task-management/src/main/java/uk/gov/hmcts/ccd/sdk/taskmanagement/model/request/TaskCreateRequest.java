package uk.gov.hmcts.ccd.sdk.taskmanagement.model.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskCreateRequest(TaskPayload task) {
}
