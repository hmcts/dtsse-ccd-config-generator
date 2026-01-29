package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode
@ToString
public class TaskSearchParameterBoolean implements TaskSearchParameter<Boolean> {

  private final TaskSearchKey key;
  private final TaskSearchOperator operator;
  private final boolean values;

  @JsonCreator
  public TaskSearchParameterBoolean(TaskSearchKey key, TaskSearchOperator operator, boolean values) {
    this.key = key;
    this.operator = operator;
    this.values = values;
  }

  @Override
  public TaskSearchKey getKey() {
    return key;
  }

  @Override
  public TaskSearchOperator getOperator() {
    return operator;
  }

  @Override
  @JsonProperty("value")
  public Boolean getValues() {
    return values;
  }
}
