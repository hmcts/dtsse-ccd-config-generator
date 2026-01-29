package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SearchTaskRequest {
  private List<TaskSearchParameter<?>> searchParameters;
  private List<TaskSortingParameter> taskSortingParameters;
  private TaskRequestContext requestContext;
}
