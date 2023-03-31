package uk.gov.hmcts.ccd.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.reform.ccd.client.model.CallbackResponse;

import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Jacksonized
public class AboutToStartOrSubmitCallbackResponse implements CallbackResponse {

  private Map<String, Object> data;

  @JsonProperty("data_classification")
  private Map<String, Object> dataClassification;

  @JsonProperty("security_classification")
  private String securityClassification;

  private List<String> errors;

  private List<String> warnings;

  private String  state;

}
