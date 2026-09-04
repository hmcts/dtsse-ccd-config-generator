package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A complex type carrying both an ungated member and a {@code @CCD(gate)}-gated member. Models ET's
 * {@code UnavailabilityDateRange}/{@code sendNotificationCollection}, whose per-environment members
 * exist only in an overlay: a field-level gate on the CaseData class cannot express a member that
 * lives inside a shared complex type, so the gate goes on the member itself.
 *
 * <p>When {@code CCD_DEF_JO} is unset the gate is inactive and {@link #gatedMember} vanishes from
 * this type's {@code ComplexTypes} rows — and because {@link GatedMemberNested} is reachable only
 * through it, that nested type disappears entirely too. {@link #alwaysMember} is byte-identical
 * across both polarities.
 */
@Data
@ComplexType(name = "GatedMemberComplex", generate = true)
public class GatedMemberComplex {

  @CCD(label = "An always-present member")
  private String alwaysMember;

  @CCD(label = "A Judgments-Online member", gate = "CCD_DEF_JO:true")
  private GatedMemberNested gatedMember;
}
