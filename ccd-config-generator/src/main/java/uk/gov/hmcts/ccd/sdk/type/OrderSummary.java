package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Data
@ComplexType(name = "OrderSummary", generate = false)
public class OrderSummary {

  @JsonProperty("PaymentReference")
  private final String paymentReference;

  @JsonProperty("Fees")
  private final List<ListValue<Fee>> fees;

  @JsonProperty("PaymentTotal")
  private final String paymentTotal;

}
