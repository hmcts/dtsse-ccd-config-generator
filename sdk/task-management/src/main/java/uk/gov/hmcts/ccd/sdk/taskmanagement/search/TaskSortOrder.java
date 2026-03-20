package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskSortOrder {

  ASCENDANT("asc"),
  DESCENDANT("desc");

  @JsonValue
  private final String id;

  TaskSortOrder(String id) {
    this.id = id;
  }

  @Override
  public String toString() {
    return id;
  }
}
