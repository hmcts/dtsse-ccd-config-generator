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
 * {@code UseCase}:
 *
 * <ul>
 *   <li>a plain field gets the default empty {@code UserRole} + {@code orgcases};</li>
 *   <li>the same {@code caseName} field appears once per {@code (UserRole, UseCase)} it is scoped to,
 *       kept distinct because both columns are part of the row's merge key;</li>
 *   <li>the fluent and positional overloads both emit their own
 *       {@code DisplayContextParameter}/{@code ResultsOrdering} values.</li>
 * </ul>
 */
@Component
public class SearchCasesRoleCaseType
    implements CCDConfig<SearchCasesRoleCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<SearchCasesRoleCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("SearchCasesRole", "SearchCasesRole", "Search cases role/use-case case type");

    builder.searchCasesFields()
        .field(SearchCasesRoleCaseData::getCaseName, "Case name")
        .field(SearchCasesRoleCaseData::getCaseName, "Case name",
            f -> f.role(LOCAL_AUTHORITY).useCase("WORKBASKET"))
        .field(SearchCasesRoleCaseData::getCaseName, "Case name",
            f -> f.role(HMCTS_ADMIN).useCase("SEARCH"))
        .field("[CASE_REFERENCE]", "Case Number",
            f -> f.displayContextParameter("#DATETIMEDISPLAY(d MMMM yyyy)").resultsOrdering("1:ASC"))
        .field("[CREATED_DATE]", "Created", "#DATETIMEDISPLAY(dd/MM/yyyy)", "createdLeaf", "2:DESC");
  }
}
