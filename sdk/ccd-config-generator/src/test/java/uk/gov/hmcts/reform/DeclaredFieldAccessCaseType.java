package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.ExplicitState.Open;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A tab places four things: a field declaring its own access, a field declaring none, a tab label,
 * and — on a second tab scoped to one role — a field declaring none. Only the first keeps the
 * access it declares; the rest still take read from the tab they appear on.
 */
@Component
public class DeclaredFieldAccessCaseType
    implements CCDConfig<DeclaredFieldAccessCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<DeclaredFieldAccessCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("DeclaredFieldAccess", "DeclaredFieldAccess", "Declared field access case type");
    builder.tabsRespectDeclaredFieldAccess();

    builder.event("edit")
        .forState(Open)
        .name("Edit")
        .grant(CRU, HMCTS_ADMIN)
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(DeclaredFieldAccessCaseData::getSharedField);

    builder.tab("overview", "Overview")
        .field(DeclaredFieldAccessCaseData::getSolicitorOnlyPanel)
        .field(DeclaredFieldAccessCaseData::getTabOnlyField)
        .label("overviewLabel", null, "A label");

    builder.tab("judicial", "Judicial")
        .forRoles(HMCTS_ADMIN)
        .field(DeclaredFieldAccessCaseData::getRoleScopedTabField);
  }
}
