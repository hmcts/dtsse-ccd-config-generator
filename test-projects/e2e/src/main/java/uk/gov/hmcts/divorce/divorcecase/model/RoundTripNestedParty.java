package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.NoArgsConstructor;

/**
 * {@link RoundTripParty} as a plain nested complex type. Uses Java field names on the wire,
 * because the config generator names complex type fields by their Java names.
 */
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class RoundTripNestedParty extends RoundTripParty {
}
