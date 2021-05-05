package uk.gov.hmcts.ccd.sdk.type;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@AllArgsConstructor
@Builder
@Data
@Jacksonized
@ComplexType(name = "MoneyGBP", generate = false)
public class MoneyGBP {

  private final String amount;

  public int toInt() {
    return Integer.parseInt(amount);
  }
}
