package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.InheritedMemberState.Open;
import static uk.gov.hmcts.reform.InheritedMemberState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A case type whose three parties inherit their common members from one shared base, and whose
 * definition needs those members configured differently per party: gated on a show condition for the
 * representative, absent altogether for the joint party, and untouched for the appellant. The
 * snapshot pins all three, which a field-level {@code @CCD} on the single shared declaration cannot
 * express — see {@link uk.gov.hmcts.ccd.sdk.api.CCD#member()}.
 */
@Component
public class InheritedMemberCaseType
    implements CCDConfig<InheritedMemberCaseData, InheritedMemberState, UserRole> {

  @Override
  public void configure(
      ConfigBuilder<InheritedMemberCaseData, InheritedMemberState, UserRole> builder) {
    builder.caseType("InheritedMember", "Inherited member",
        "Per-subclass inherited-member configuration case type");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(InheritedMemberCaseData::getBaseField)
        .optional(InheritedMemberCaseData::getAppellant)
        .optional(InheritedMemberCaseData::getRepresentative);
  }
}
