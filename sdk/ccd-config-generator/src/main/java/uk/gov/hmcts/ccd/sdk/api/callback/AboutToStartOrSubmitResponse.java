package uk.gov.hmcts.ccd.sdk.api.callback;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.EventMetadata;
import uk.gov.hmcts.reform.ccd.client.model.SignificantItem;

@Builder
@Data
public class AboutToStartOrSubmitResponse<T, S> {
  private T data;

  @JsonProperty("error_message_override")
  private String errorMessageOverride;

  private List<String> errors;

  private List<String> warnings;

  private S state;

  @JsonProperty("data_classification")
  private Map<String, Object> dataClassification;

  @JsonProperty("security_classification")
  private String securityClassification;

  @JsonIgnore
  private EventMetadata eventMetadata;

  @JsonProperty("significant_item")
  private SignificantItem significantItem;
}
