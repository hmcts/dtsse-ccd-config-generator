package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.ExplicitState.Open;
import static uk.gov.hmcts.reform.ExplicitState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A case type placing both an ungated and a {@code @CCD(gate)}-gated field on the same event, tab
 * and search sheet. When the gate is inactive the gated field's CaseField,
 * AuthorisationCaseField, CaseEventToFields, CaseTypeTab and SearchInputFields rows all vanish
 * together, leaving the ungated field's rows untouched; when active they all appear. The typed
 * {@code GatedFieldCaseData::getGatedField} placement compiles either way because the Java member
 * always exists — only the emitted rows are gated.
 */
@Component
public class GatedFieldCaseType
    implements CCDConfig<GatedFieldCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<GatedFieldCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("GatedField", "Gated field", "Env-gated field case type");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(GatedFieldCaseData::getBaseField)
        .optional(GatedFieldCaseData::getGatedField);

    builder.tab("primary", "Primary")
        .field(GatedFieldCaseData::getBaseField)
        .field(GatedFieldCaseData::getGatedField);

    builder.searchInputFields()
        .field(GatedFieldCaseData::getBaseField, "A base field")
        .field(GatedFieldCaseData::getGatedField, "A Judgments-Online field");
  }
}
