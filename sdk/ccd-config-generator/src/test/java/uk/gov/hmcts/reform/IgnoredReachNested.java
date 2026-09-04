package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A complex type reachable <em>only</em> through fields marked {@code @CCD(ignore = true)} and
 * {@code @JsonIgnore} (see {@link IgnoredReachCaseData}). Such a type must emit no
 * {@code ComplexTypes} rows: no field in the generated definition references it, so declaring it
 * would put a complex type in the definition that nothing uses.
 *
 * <p>Mirrors {@link GatedMemberNested}, whose type vanishes when its only referencing field's
 * {@code @CCD(gate)} is inactive — ignored fields now behave the same way at reachability.
 */
@Data
@ComplexType(name = "IgnoredReachNested", generate = true)
public class IgnoredReachNested {

  @CCD(label = "A detail nothing in the definition reaches")
  private String unreachableDetail;
}
