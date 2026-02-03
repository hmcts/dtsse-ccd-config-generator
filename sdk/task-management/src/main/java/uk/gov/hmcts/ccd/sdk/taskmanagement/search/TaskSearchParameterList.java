package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class TaskSearchParameterList implements TaskSearchParameter<List<String>> {

  private final TaskSearchKey key;

  private final TaskSearchOperator operator;
  private final List<String> values;

  @JsonCreator
  public TaskSearchParameterList(TaskSearchKey key, TaskSearchOperator operator, List<String> values) {
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
  public List<String> getValues() {
    return values;
  }
}
