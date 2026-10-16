package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * A {@code @JsonNaming} class whose member already carries its OWN {@code @JsonProperty}. By Jackson's
 * precedence that annotation overrides the class strategy, so it already decided the CCD id and the
 * patch must never add a second one — the field would then carry two conflicting {@code @JsonProperty}
 * annotations and not compile.
 */
@Data
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class AnnotatedNamedAddress {

  @JsonProperty("CountyName")
  private String county;
}
