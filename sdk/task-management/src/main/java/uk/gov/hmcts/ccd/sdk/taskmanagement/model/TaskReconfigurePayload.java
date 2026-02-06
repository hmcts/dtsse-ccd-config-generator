package uk.gov.hmcts.ccd.sdk.taskmanagement.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPermission;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPermissionListDeserializer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TaskReconfigurePayload {
  String id;
  String title;
  String caseCategory;
  String caseName;
  String region;
  String location;
  String workType;
  String roleCategory;
  String description;
  OffsetDateTime dueDateTime;
  OffsetDateTime priorityDate;
  Integer majorPriority;
  Integer minorPriority;
  String locationName;
  Map<String, Object> additionalProperties;
  @JsonDeserialize(using = TaskPermissionListDeserializer.class)
  List<TaskPermission> permissions;
}
