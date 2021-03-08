package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Data
@ComplexType(name = "Fee", generate = false)
public class Fee {

  @JsonProperty("FeeAmount")
  private final String amount;

  @JsonProperty("FeeCode")
  private final String code;

  @JsonProperty("FeeDescription")
  private final String description;

  @JsonProperty("FeeVersion")
  private final String version;

}
