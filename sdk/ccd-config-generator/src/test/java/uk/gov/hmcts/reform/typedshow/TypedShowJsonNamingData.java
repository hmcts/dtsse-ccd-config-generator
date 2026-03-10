package uk.gov.hmcts.reform.typedshow;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TypedShowJsonNamingData {

  private String namingValue;

  public String getNamingValue() {
    return namingValue;
  }
}
