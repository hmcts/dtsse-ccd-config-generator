package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode
@ToString
public class TaskSortingParameter {

  private final TaskSortField sortBy;
  private final TaskSortOrder sortOrder;

  public TaskSortingParameter(TaskSortField sortBy, TaskSortOrder sortOrder) {
    this.sortBy = sortBy;
    this.sortOrder = sortOrder;
  }

  public TaskSortField getSortBy() {
    return sortBy;
  }

  public TaskSortOrder getSortOrder() {
    return sortOrder;
  }
}
