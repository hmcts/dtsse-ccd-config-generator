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
 * The {@code overview} tab names no roles and places six things: a field naming
 * {@code SolicitorAccess}, a field naming nothing, a field naming an access class that grants
 * nothing, a field naming one that grants write but not read, a field inside a
 * {@code @JsonUnwrapped} parent that names access but which names none itself, and a label. The
 * {@code judicial} and {@code solicitorPanel} tabs each name one role and place a field naming
 * {@code SolicitorAccess}: the first a role that access class does not grant, the second the role
 * it does.
 *
 * <p>So the checked-in sheet shows every field {@code SolicitorAccess} reaches readable only by the
 * solicitor, whichever tab it sits on and whether that tab names roles or not; the field naming an
 * access class that grants nothing readable by nobody; the field granting write but not read
 * emitted as {@code CU}, where the tab previously supplied the {@code R}; and the field naming
 * nothing and the label still taking read from the tab.
 *
 * <p>The {@code CaseHistory} tab names no roles; the solicitor is in {@code omitHistoryForRoles} and
 * has no {@code caseHistory} row.
 */
@Component
public class DeclaredFieldAccessCaseType
    implements CCDConfig<DeclaredFieldAccessCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<DeclaredFieldAccessCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("DeclaredFieldAccess", "DeclaredFieldAccess", "Declared field access case type");
    builder.tabsRespectDeclaredFieldAccess();
    builder.omitHistoryForRoles(LOCAL_AUTHORITY);

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
        .field(DeclaredFieldAccessCaseData::getWriteWithoutReadField)
        .field("unwrappedChild")
        .label("overviewLabel", null, "A label");

    builder.tab("CaseHistory", "History")
        .field("caseHistory");
    // A field declaring access only for the solicitor, on a tab built for the courtadmin. The
    // label still takes read from the tab, so the courtadmin is left with the header alone.
    builder.tab("judicial", "Judicial")
        .forRoles(HMCTS_ADMIN)
        .label("judicialLabel", null, "Orders")
        .field(DeclaredFieldAccessCaseData::getRoleScopedTabField);

    // The same declaration, on a tab built for the role it grants: nothing is lost.
    builder.tab("solicitorPanel", "Solicitor panel")
        .forRoles(LOCAL_AUTHORITY)
        .field(DeclaredFieldAccessCaseData::getSolicitorScopedTabField);
  }
}
