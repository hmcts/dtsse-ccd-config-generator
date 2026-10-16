package uk.gov.hmcts.reform;

import lombok.Data;
import lombok.EqualsAndHashCode;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

/**
 * The subclass that overrides the two members it shares with everyone else not at all — they emit
 * rows carrying the base declaration's own metadata, proving an override is scoped to the class that
 * declares it rather than leaking across the hierarchy.
 *
 * <p>It IS the one subclass the definition has a {@code role} row for, though, and that member's
 * declaration is {@code ignore = true} because nobody else does. So its override carries the whole
 * configuration — including the {@code typeParameterClass} naming a list reachable through nothing
 * else, which pins that the override is read where a type's reachability is decided too, not only
 * where a row's metadata is.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ComplexType(name = "InheritedMemberAppellant", generate = true)
@CCD(
    member = "role",
    label = "Appellant role",
    typeOverride = FieldType.FixedList,
    typeParameterOverride = "FL_inheritedMemberRoles",
    typeParameterClass = InheritedMemberRole.class)
public class InheritedMemberAppellant extends InheritedMemberParty {

  @CCD(label = "Is appointee")
  private String isAppointee;
}
