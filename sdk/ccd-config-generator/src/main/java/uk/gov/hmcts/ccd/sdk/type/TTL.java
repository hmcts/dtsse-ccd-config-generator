package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "TTL")
public class TTL {

  @JsonProperty("SystemTTL")
  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate systemTTL;

  @JsonProperty("Suspended")
  private YesOrNo suspended;

  @JsonProperty("OverrideTTL")
  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate overrideTTL;

  @JsonCreator
  public TTL(
      @JsonProperty("SystemTTL") LocalDate systemTTL,
      @JsonProperty("Suspended") YesOrNo suspended,
      @JsonProperty("OverrideTTL") LocalDate overrideTTL
  ) {
    this.systemTTL = systemTTL;
    this.suspended = suspended;
    this.overrideTTL = overrideTTL;
  }
}
