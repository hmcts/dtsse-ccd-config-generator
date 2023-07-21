package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@ComplexType(generate = false)
public class LinkReason {

  @JsonProperty("Reason")
  private String reason;

  @JsonProperty("OtherDescription")
  private String description;

  @JsonCreator
  public LinkReason(
      @JsonProperty("Reason") String reason,
      @JsonProperty("OtherDescription") String description
  ) {
    this.reason = reason;
    this.description = description;
  }
}
