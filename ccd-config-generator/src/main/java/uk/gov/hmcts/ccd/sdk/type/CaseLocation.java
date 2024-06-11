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

  @JsonProperty("Region")
  private String region;

  @JsonProperty("BaseLocation")
  private String baseLocation;

  @JsonCreator
  public CaseLocation(@JsonProperty("Region") String region,
                      @JsonProperty("BaseLocation") String baseLocation) {
    this.region = region;
    this.baseLocation = baseLocation;
  }

}
