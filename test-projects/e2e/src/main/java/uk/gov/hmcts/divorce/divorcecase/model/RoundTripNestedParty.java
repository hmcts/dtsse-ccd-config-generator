package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.NoArgsConstructor;

/**
 * {@link RoundTripParty} as a plain nested complex type with a different naming strategy,
 * covering a subclass override of the strategy used for inherited fields.
 */
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class RoundTripNestedParty extends RoundTripParty {
}
