package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data

@ComplexType(name = "Fee", generate = false)
public class Fee {

  @JsonProperty("FeeAmount")
  private String amount;

  @JsonProperty("FeeCode")
  private String code;

  @JsonProperty("FeeDescription")
  private String description;

  @JsonProperty("FeeVersion")
  private String version;

  @JsonCreator
  public Fee(
      @JsonProperty("FeeAmount") String amount,
      @JsonProperty("FeeCode") String code,
      @JsonProperty("FeeDescription") String description,
      @JsonProperty("FeeVersion") String version
  ) {
    this.amount = amount;
    this.code = code;
    this.description = description;
    this.version = version;
  }

  public static String getValueInPence(double value) {
    return BigDecimal.valueOf(value).movePointRight(2).toPlainString();
  }
}
