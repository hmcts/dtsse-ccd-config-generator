package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "OrderSummary", generate = false)
public class OrderSummary {

  @JsonProperty("PaymentReference")
  private String paymentReference;

  @JsonProperty("Fees")
  private List<ListValue<Fee>> fees;

  @JsonProperty("PaymentTotal")
  private String paymentTotal;

  @JsonCreator
  public OrderSummary(
      @JsonProperty("PaymentReference") String paymentReference,
      @JsonProperty("Fees") List<ListValue<Fee>> fees,
      @JsonProperty("PaymentTotal") String paymentTotal
  ) {
    this.paymentReference = paymentReference;
    this.fees = fees;
    this.paymentTotal = paymentTotal;
  }
}
