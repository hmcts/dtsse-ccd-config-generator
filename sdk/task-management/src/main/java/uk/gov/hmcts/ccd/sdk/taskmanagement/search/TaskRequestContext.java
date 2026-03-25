package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;


@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum TaskRequestContext {
  ALL_WORK("ALL_WORK"),
  AVAILABLE_TASKS("AVAILABLE_TASKS");

  @JsonValue
  private final String id;

  TaskRequestContext(String id) {
    this.id = id;
  }

  @Override
  public String toString() {
    return id;
  }
}
