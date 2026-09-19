package uk.gov.hmcts.ccd.sdk.taskmanagement.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TaskPermission {
  String roleName;
  String roleCategory;
  List<String> permissions;
  List<String> authorisations;
  Integer assignmentPriority;
  Boolean autoAssignable;
}
