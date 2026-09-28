package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;

/**
 * A fixed list reachable ONLY through a class-level {@code @CCD(member)} override's
 * {@code typeParameterClass} — see {@link InheritedMemberAppellant}. The member it types is declared
 * {@code @CCD(ignore = true)} on the shared base, because only one subclass has a row for it, so a
 * resolver reading the declaration alone finds no {@code typeParameterClass} and this list emits no
 * rows at all.
 *
 * <p>sscs's shape exactly: abstract {@code Party} declares {@code ibcRole}, the definition has a row
 * for it under {@code appellant} only, and {@code FL_ibcRoles}' five rows went missing.
 */
@ComplexType(name = "FL_inheritedMemberRoles", generate = true)
public enum InheritedMemberRole implements HasLabel {
  MYSELF,
  GUARDIAN;

  @Override
  public String getLabel() {
    return this == MYSELF ? "I am appealing for myself" : "I am appealing as a guardian";
  }
}
