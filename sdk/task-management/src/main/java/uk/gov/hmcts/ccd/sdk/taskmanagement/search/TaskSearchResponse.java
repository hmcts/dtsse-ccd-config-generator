package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaskSearchResponse {
  private List<TaskPayload> tasks;
  private long totalRecords;
}
