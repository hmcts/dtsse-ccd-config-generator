package uk.gov.hmcts.ccd.sdk.taskmanagement.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TaskPayload {
  String externalTaskId;
  String id;
  String type;
  String name;
  String title;
  String assignee;
  OffsetDateTime created;
  String executionType;
  String caseId;
  String caseTypeId;
  String caseCategory;
  String caseName;
  String jurisdiction;
  String region;
  String location;
  String workType;
  String roleCategory;
  String securityClassification;
  String description;
  OffsetDateTime dueDateTime;
  OffsetDateTime priorityDate;
  Integer majorPriority;
  Integer minorPriority;
  String locationName;
  String regionName;
  String taskSystem;
  Map<String, Object> additionalProperties;
  @JsonDeserialize(using = TaskPermissionListDeserializer.class)
  List<TaskPermission> permissions;
}
