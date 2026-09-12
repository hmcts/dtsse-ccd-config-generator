package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A complex type reachable <em>only</em> through {@link GatedMemberComplex#gatedMember}, whose
 * {@code @CCD(gate)} is inactive when {@code CCD_DEF_JO} is unset. When the gate is off this whole
 * type must vanish from the generated {@code ComplexTypes} directory — no field anywhere else
 * references it — mirroring how {@code ConfigResolver} already drops a nested type reachable only
 * through a gated-off top-level field.
 */
@Data
@ComplexType(name = "GatedMemberNested", generate = true)
public class GatedMemberNested {

  @CCD(label = "A nested detail")
  private String nestedDetail;
}
