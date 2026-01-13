package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TaskPayload {
  String taskId;
  String type;
  String name;
  String title;
  String state;
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
  List<TaskPermission> permissions;
}
