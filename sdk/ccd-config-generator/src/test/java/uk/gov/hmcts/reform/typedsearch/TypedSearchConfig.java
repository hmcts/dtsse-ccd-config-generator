package uk.gov.hmcts.reform.typedsearch;

import static uk.gov.hmcts.ccd.sdk.api.SortOrder.FIFTH;
import static uk.gov.hmcts.ccd.sdk.api.SortOrder.FIRST;
import static uk.gov.hmcts.ccd.sdk.api.SortOrder.FOURTH;
import static uk.gov.hmcts.ccd.sdk.api.SortOrder.SECOND;
import static uk.gov.hmcts.ccd.sdk.api.SortOrder.THIRD;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.ShowCondition;

@Component
public class TypedSearchConfig implements CCDConfig<TypedSearchCaseData, TypedSearchState, TypedSearchRole> {

  @Override
  public void configure(ConfigBuilder<TypedSearchCaseData, TypedSearchState, TypedSearchRole> builder) {
    builder.caseType("typed_search", "Typed search", "Typed search overload test case type");

    ShowCondition openStatus = ShowCondition.field("status").is("OPEN");

    builder.searchInputFields()
        .fieldIf(TypedSearchCaseData::getStatus, "Status", ShowCondition.stateIs("Open"))
        .fieldIf(TypedSearchCaseData::getReviewedOn, "Reviewed on", openStatus, "#DATETIMEDISPLAY(d MMM yyyy)")
        .fieldIf(TypedSearchCaseData::getSubmittedOn, "Submitted on", openStatus, FIRST.DESCENDING,
            "#DATETIMEDISPLAY(d MMM yyyy)")
        .fieldIf("aliasBasic", "Alias basic", "code", openStatus)
        .fieldIf("aliasDisplay", "Alias display", "code", openStatus, "#TABLE(aliasDisplay)")
        .fieldIf("aliasRole", "Alias role", "code", openStatus, TypedSearchRole.CASEWORKER)
        .fieldIf("aliasRoleDisplay", "Alias role display", "code", openStatus, TypedSearchRole.CASEWORKER,
            "#TABLE(aliasRoleDisplay)")
        .fieldIf("aliasOrdered", "Alias ordered", "code", openStatus, SECOND.ASCENDING)
        .fieldIf("aliasRoleOrdered", "Alias role ordered", "code", openStatus, TypedSearchRole.CASEWORKER,
            THIRD.DESCENDING)
        .fieldIf("aliasDisplayOrdered", "Alias display ordered", "code", openStatus,
            "#TABLE(aliasDisplayOrdered)", FOURTH.ASCENDING)
        .fieldIf("aliasRoleDisplayOrdered", "Alias role display ordered", "code", openStatus,
            TypedSearchRole.CASEWORKER, "#TABLE(aliasRoleDisplayOrdered)", FIFTH.DESCENDING);
  }
}
