package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskCreateResponse(
    String taskId,
    String taskName,
    String taskType,
    String state,
    String caseId,
    String caseTypeId,
    String jurisdiction,
    OffsetDateTime created
) {
}
