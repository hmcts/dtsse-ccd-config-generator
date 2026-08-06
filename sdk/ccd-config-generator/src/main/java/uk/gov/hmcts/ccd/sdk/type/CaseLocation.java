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
@ComplexType(name = "CaseLocation")
public class CaseLocation {

  @JsonProperty("region")
  private String region;

  @JsonProperty("baseLocation")
  private String baseLocation;

  @JsonCreator
  public CaseLocation(@JsonProperty("region") String region,
                      @JsonProperty("baseLocation") String baseLocation) {
    this.region = region;
    this.baseLocation = baseLocation;
  }

}
