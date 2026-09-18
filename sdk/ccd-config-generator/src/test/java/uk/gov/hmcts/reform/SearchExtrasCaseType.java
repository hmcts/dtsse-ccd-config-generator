package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.SortOrder.FIRST;
import static uk.gov.hmcts.ccd.sdk.api.SortOrder.SECOND;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Exercises the search/workbasket {@code field(id, label, Consumer)} sub-builder for the columns the
 * plain overloads cannot express — {@code ListElementCode}, {@code FieldShowCondition} and
 * {@code ResultsOrdering} — and their composition with role scoping.
 *
 * <p>The applicant complex field is searched one leaf at a time: two calls with different
 * {@code listElementCode} emit two rows for the same {@code (CaseFieldID, UserRole)}, which the
 * generator keeps distinct because {@code ListElementCode} is part of the row's merge key. Input
 * sheets carry {@code FieldShowCondition} (rejected on result sheets by the importer) and result
 * sheets carry {@code ResultsOrdering} (rejected on input sheets), matching the definition store's
 * per-sheet rules.
 */
@Component
public class SearchExtrasCaseType
    implements CCDConfig<SearchExtrasCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<SearchExtrasCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("SearchExtras", "SearchExtras", "Search extras case type");

    // Input sheets: FieldShowCondition + ListElementCode (two leaves of the same complex field), plus
    // a role-scoped leaf to prove the sub-builder composes with UserRole scoping.
    builder.searchInputFields()
        .field(SearchExtrasCaseData::getCaseName, "Case name",
            f -> f.showCondition("applicant.surname=\"*\""))
        .field(SearchExtrasCaseData::getApplicant, "Applicant forename",
            f -> f.listElementCode("forename"))
        .field(SearchExtrasCaseData::getApplicant, "Applicant surname",
            f -> f.listElementCode("surname").showCondition("caseName=\"x\""))
        .field(SearchExtrasCaseData::getApplicant, "Applicant surname (admin)",
            f -> f.listElementCode("surname").role(HMCTS_ADMIN));

    builder.workBasketInputFields()
        .field(SearchExtrasCaseData::getApplicant, "Applicant forename",
            f -> f.listElementCode("forename").role(LOCAL_AUTHORITY));

    // Result sheets: ResultsOrdering + ListElementCode.
    builder.searchResultFields()
        .field(SearchExtrasCaseData::getApplicant, "Applicant surname",
            f -> f.listElementCode("surname").resultsOrdering(FIRST.DESCENDING))
        .field(SearchExtrasCaseData::getApplicant, "Applicant forename",
            f -> f.listElementCode("forename").resultsOrdering(SECOND.ASCENDING));

    builder.workBasketResultFields()
        .field(SearchExtrasCaseData::getApplicant, "Applicant surname",
            f -> f.listElementCode("surname").resultsOrdering(FIRST.ASCENDING));
  }
}
