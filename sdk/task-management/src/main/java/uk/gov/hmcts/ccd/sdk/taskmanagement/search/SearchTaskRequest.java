package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import com.fasterxml.jackson.annotation.JsonProperty;
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
  @JsonProperty("search_parameters")
  private List<TaskSearchParameter<?>> searchParameters;
  @JsonProperty("sorting_parameters")
  private List<TaskSortingParameter> taskSortingParameters;
  @JsonProperty("request_context")
  private TaskRequestContext requestContext;
}
