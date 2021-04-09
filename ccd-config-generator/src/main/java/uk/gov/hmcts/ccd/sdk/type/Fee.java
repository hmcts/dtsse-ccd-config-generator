package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@AllArgsConstructor
@Builder
@Data
@Jacksonized
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

  public static String getValueInPence(double value) {
    return BigDecimal.valueOf(value).movePointRight(2).toPlainString();
  }
}
