package uk.gov.hmcts.reform;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;

/**
 * A reference-data list no field in the model declares: the case-data field carrying it is a
 * {@code String} plus {@code @CCD(typeParameterOverride)}, so this enum is reachable only through
 * {@code @CCD(typeParameterClass)}. Its {@code @ComplexType(name)} pins the wire list ID, so the
 * emitted {@code FixedLists} ID is the definition's own, not the Java simple name.
 */
@ComplexType(name = "FL_typeParamReachVenues", generate = true)
@Getter
@AllArgsConstructor
public enum TypeParamReachVenue implements HasLabel {
  _219164("Aberdeen", "219164"),
  _450049("Aldershot", "450049");

  private final String label;

  @JsonValue
  private final String code;
}
