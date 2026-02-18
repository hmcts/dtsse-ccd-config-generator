package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import static java.util.Arrays.stream;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum TaskSearchOperator {

  IN("IN"),
  CONTEXT("CONTEXT"),
  BOOLEAN("BOOLEAN"),
  BETWEEN("BETWEEN"),
  BEFORE("BEFORE"),
  AFTER("AFTER");

  @JsonValue
  private String value;

  TaskSearchOperator(String value) {
    this.value = value;
  }

  public static TaskSearchOperator from(String value) {
    return stream(values())
        .filter(v -> v.getValue().equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(value + " is an unsupported operator"));
  }

  @Override
  public String toString() {
    return value;
  }
}
