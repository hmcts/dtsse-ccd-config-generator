package uk.gov.hmcts.ccd.sdk.taskmanagement.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskCreateResponse(
    String taskId,
    String externalTaskId,
    String taskName,
    String taskType,
    OffsetDateTime dueDateTime,
    String state,
    String taskSystem,
    String securityClassification,
    String title,
    String description,
    List<Object> notes,
    Integer majorPriority,
    Integer minorPriority,
    Boolean autoAssigned,
    Map<String, Object> workTypeResource,
    String roleCategory,
    Boolean hasWarnings,
    String caseId,
    String caseTypeId,
    String caseName,
    String caseCategory,
    String jurisdiction,
    String region,
    String regionName,
    String location,
    String locationName,
    OffsetDateTime created,
    Map<String, Object> executionTypeCode,
    Map<String, Object> additionalProperties,
    OffsetDateTime priorityDate,
    Boolean indexed
) implements TaskApiResponse {
}
