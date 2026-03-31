package uk.gov.hmcts.ccd.sdk.taskmanagement.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum TaskAction {
  COMPLETE("complete"),
  CANCEL("cancel"),
  INITIATE("initiate"),
  RECONFIGURE("reconfigure");

  @JsonValue
  private final String id;

  TaskAction(String id) {
    this.id = id;
  }

  public static TaskAction fromId(String id) {
    for (TaskAction action : values()) {
      if (action.id.equalsIgnoreCase(id)) {
        return action;
      }
    }
    throw new IllegalArgumentException("Unknown task action id: " + id);
  }
}
