package uk.gov.hmcts.reform;

import lombok.Data;
import lombok.EqualsAndHashCode;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * The subclass whose inherited members the definition gates on a show condition, and whose
 * {@code organisation} it labels differently. Both come from class-level {@code @CCD(member)}
 * overrides, so {@link InheritedMemberAppellant}'s rows for the same two members are untouched.
 *
 * <p>sscs's shape: {@code Representative}'s five {@code Entity} members carry
 * {@code FieldShowCondition=hasRepresentative="Yes"} and nobody else's do.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ComplexType(name = "InheritedMemberRepresentative", generate = true)
@CCD(member = "partyName", label = "Name", showCondition = "hasRepresentative=\"Yes\"")
@CCD(member = "organisation", label = "Representative organisation")
public class InheritedMemberRepresentative extends InheritedMemberParty {

  @CCD(label = "Has representative")
  private String hasRepresentative;
}
