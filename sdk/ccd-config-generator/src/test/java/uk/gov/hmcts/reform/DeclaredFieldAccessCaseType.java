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
 * The {@code overview} tab names no roles and places five things: a field naming
 * {@code SolicitorAccess}, a field naming nothing, a field naming an access class that grants
 * nothing, a field inside a {@code @JsonUnwrapped} parent that names access but which names none
 * itself, and a label. The {@code judicial} tab names {@code HMCTS_ADMIN} and places a field naming
 * {@code SolicitorAccess}.
 *
 * <p>So the checked-in sheet shows the first readable only by the solicitor, the third readable by
 * nobody, and the rest still taking read from the tab they are on — including the field on the
 * tab that names a role, which keeps read for that role.
 */
@Component
public class DeclaredFieldAccessCaseType
    implements CCDConfig<DeclaredFieldAccessCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<DeclaredFieldAccessCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("DeclaredFieldAccess", "DeclaredFieldAccess", "Declared field access case type");
    builder.unscopedTabsRespectDeclaredFieldAccess();

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
        .field(DeclaredFieldAccessCaseData::getNoGrantsField)
        .field("unwrappedChild")
        .label("overviewLabel", null, "A label");

    // A field declaring access only for the solicitor, on a tab built for the courtadmin.
    builder.tab("judicial", "Judicial")
        .forRoles(HMCTS_ADMIN)
        .field(DeclaredFieldAccessCaseData::getRoleScopedTabField);
  }
}
