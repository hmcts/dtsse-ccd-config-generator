package uk.gov.hmcts.ccd.sdk.api.callback;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class AboutToStartOrSubmitResponse<T, S> {
  private T data;

  private List<String> errors;

  private List<String> warnings;

  private S state;

  @JsonProperty("data_classification")
  private Map<String, Object> dataClassification;

  @JsonProperty("security_classification")
  private String securityClassification;

}
