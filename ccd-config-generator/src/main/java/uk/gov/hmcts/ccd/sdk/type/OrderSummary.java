package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@AllArgsConstructor
@Builder
@Data
@Jacksonized
@ComplexType(name = "OrderSummary", generate = false)
public class OrderSummary {

  @JsonProperty("PaymentReference")
  private final String paymentReference;

  @JsonProperty("Fees")
  private final List<ListValue<Fee>> fees;

  @JsonProperty("PaymentTotal")
  private final String paymentTotal;

}
