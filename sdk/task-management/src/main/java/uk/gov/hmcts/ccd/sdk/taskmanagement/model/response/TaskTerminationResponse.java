package uk.gov.hmcts.ccd.sdk.taskmanagement.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskTerminationResponse(List<TaskPayload> tasks) implements TaskApiResponse {
}
