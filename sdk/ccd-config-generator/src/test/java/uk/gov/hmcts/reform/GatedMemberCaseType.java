package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.GatedMemberState.Open;
import static uk.gov.hmcts.reform.GatedMemberState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A case type whose complex field carries a {@code @CCD(gate)}-gated member. When the gate is
 * inactive the member's {@code ComplexTypes} row vanishes — and the nested complex type reachable
 * only through it disappears from the {@code ComplexTypes} directory entirely — while the ungated
 * member's rows and the rest of the definition stay byte-identical; when active they all appear.
 * The gate lives on the complex-type member (see {@link GatedMemberComplex}), which field-level
 * gating on the CaseData class cannot express.
 */
@Component
public class GatedMemberCaseType
    implements CCDConfig<GatedMemberCaseData, GatedMemberState, UserRole> {

  @Override
  public void configure(ConfigBuilder<GatedMemberCaseData, GatedMemberState, UserRole> builder) {
    builder.caseType("GatedMember", "Gated member", "Env-gated complex-type member case type");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(GatedMemberCaseData::getBaseField)
        .optional(GatedMemberCaseData::getComplexField);
  }
}
