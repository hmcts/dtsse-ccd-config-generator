package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * If adding a new field please ensure they are added as snake_case only and not camelCase.
 * The camelCase format is deprecated and will be removed.
 */
public enum TaskSearchKey {

  LOCATION("location"),
  USER("user"),
  JURISDICTION("jurisdiction"),
  STATE("state"),
  TASK_TYPE("task_type"),
  CASE_ID_CAMEL_CASE("caseId"),
  CASE_ID("case_id"),
  //R2 should be snake_case only,
  WORK_TYPE("work_type"),
  ROLE_CATEGORY("role_category"),
  PROCESS_CATEGORY_IDENTIFIER("process_category_identifier");

  @JsonValue
  private final String id;

  TaskSearchKey(String id) {
    this.id = id;
  }

  @Override
  public String toString() {
    return id;
  }

  public String value() {
    return id;
  }
}
