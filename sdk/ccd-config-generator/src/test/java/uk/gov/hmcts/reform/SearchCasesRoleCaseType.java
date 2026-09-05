package uk.gov.hmcts.reform;

import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Exercises the {@code searchCasesFields().field(id, label, Consumer)} sub-builder that scopes a
 * {@code SearchCasesResultFields} row to an {@code AccessProfile}/{@code UserRole} and/or a
 * {@code UseCase}. The generator previously hardcoded an empty {@code UserRole} and
 * {@code UseCase=orgcases} for every row; this fixture pins that:
 *
 * <ul>
 *   <li>a plain field keeps the historic empty {@code UserRole} + {@code orgcases} default;</li>
 *   <li>the same {@code caseName} field appears once per {@code (UserRole, UseCase)} it is scoped to,
 *       kept distinct because both columns are part of the row's merge key.</li>
 * </ul>
 */
@Component
public class SearchCasesRoleCaseType
    implements CCDConfig<SearchCasesRoleCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<SearchCasesRoleCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("SearchCasesRole", "SearchCasesRole", "Search cases role/use-case case type");

    builder.searchCasesFields()
        // Historic default row: no role (empty UserRole), no useCase (orgcases).
        .field(SearchCasesRoleCaseData::getCaseName, "Case name")
        // Same field scoped to a role under a custom use case.
        .field(SearchCasesRoleCaseData::getCaseName, "Case name",
            f -> f.role(LOCAL_AUTHORITY).useCase("WORKBASKET"))
        // Same field, different role + use case: a distinct row.
        .field(SearchCasesRoleCaseData::getCaseName, "Case name",
            f -> f.role(HMCTS_ADMIN).useCase("SEARCH"));
  }
}
