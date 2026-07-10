package uk.gov.hmcts.ccd.sdk.api.callback;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.EventMetadata;
import uk.gov.hmcts.reform.ccd.client.model.Classification;
import uk.gov.hmcts.reform.ccd.client.model.SignificantItem;

/**
 * Response returned from decentralised submit handlers.
 */
@Builder
@Data
public class SubmitResponse<State> {

  public static <State> SubmitResponse<State> defaultResponse() {
    return SubmitResponse.<State>builder().build();
  }

  private String confirmationHeader;

  private String confirmationBody;

  private List<String> errors;

  private List<String> warnings;

  private List<String> ignoreWarning;

  private State state;

  private Classification caseSecurityClassification;

  @JsonIgnore
  private EventMetadata eventMetadata;

  @JsonProperty("significant_item")
  private SignificantItem significantItem;
}
