package uk.gov.hmcts.reform;

import lombok.Data;
import lombok.EqualsAndHashCode;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * The subclass whose inherited {@code organisation} the definition has no member for at all, dropped
 * with {@code @CCD(member, ignore = true)} — the same per-subclass scoping as the metadata override,
 * applied to presence rather than to a column.
 *
 * <p>sscs's shape: {@code JointParty} inherits {@code Party}'s members through the hierarchy, and
 * because {@code SscsCaseData} holds it {@code @JsonUnwrapped} with no prefix they landed as
 * top-level {@code CaseField} rows the definition has no fields for.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@CCD(member = "organisation", ignore = true)
public class InheritedMemberJointParty extends InheritedMemberParty {

  @CCD(label = "Has joint party")
  private String hasJointParty;
}
