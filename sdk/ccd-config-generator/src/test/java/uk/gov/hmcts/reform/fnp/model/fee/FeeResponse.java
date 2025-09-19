package uk.gov.hmcts.reform.fnp.model.fee;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
public class FeeResponse {
    private String code;
    private String description;
    private Integer version;
    @JsonProperty(value = "fee_amount")
    private BigDecimal amount;
}
