package uk.gov.hmcts.reform;

import lombok.Data;
import lombok.EqualsAndHashCode;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * The subclass that overrides nothing: both inherited members emit rows carrying the base
 * declaration's own metadata, proving an override is scoped to the class that declares it rather
 * than leaking across the hierarchy.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ComplexType(name = "InheritedMemberAppellant", generate = true)
public class InheritedMemberAppellant extends InheritedMemberParty {

  @CCD(label = "Is appointee")
  private String isAppointee;
}
