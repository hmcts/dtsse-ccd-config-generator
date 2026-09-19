package uk.gov.hmcts.ccd.sdk.taskmanagement.model.request;

import java.util.List;
import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskTerminationRequest(String action, List<String> taskIds) {
}
