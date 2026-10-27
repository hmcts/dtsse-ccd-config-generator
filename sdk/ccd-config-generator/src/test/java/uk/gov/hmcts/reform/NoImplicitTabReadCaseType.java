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
 * Exercises {@link ConfigBuilder#noImplicitTabReadAccess()}. {@code adminOnlyPanel} is granted to
 * HMCTS_ADMIN through an event and placed on a tab naming no roles, which would otherwise also
 * grant LOCAL_AUTHORITY read on it. {@code searchableField} shows that search keeps its injection.
 */
@Component
public class NoImplicitTabReadCaseType
    implements CCDConfig<NoImplicitTabReadCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<NoImplicitTabReadCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("NoImplicitTabRead", "NoImplicitTabRead", "No implicit tab read case type");
    builder.noImplicitTabReadAccess();

    builder.event("edit")
        .forState(Open)
        .name("Edit")
        .grant(CRU, HMCTS_ADMIN)
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(NoImplicitTabReadCaseData::getSharedField);

    builder.event("admin")
        .forState(Open)
        .name("Admin")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .optional(NoImplicitTabReadCaseData::getAdminOnlyPanel);

    builder.tab("overview", "Overview")
        .field(NoImplicitTabReadCaseData::getAdminOnlyPanel);

    builder.searchInputFields()
        .field(NoImplicitTabReadCaseData::getSearchableField, "Searchable field");
  }
}
