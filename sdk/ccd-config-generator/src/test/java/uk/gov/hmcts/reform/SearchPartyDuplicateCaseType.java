package uk.gov.hmcts.reform;

import java.util.List;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchPartyField;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Regression fixture for the {@code SearchParty} generator's row identity. Two parties share the same
 * {@code SearchPartyName} but point at different collections
 * ({@code SearchPartyCollectionFieldName}). The generator must emit both rows: keying only on
 * {@code (CaseTypeID, SearchPartyName)} collapsed them last-wins (leaving a single {@code applicants}
 * row), so the golden snapshot pins that {@code SearchPartyCollectionFieldName} is part of the key.
 */
@Component
public class SearchPartyDuplicateCaseType
    implements CCDConfig<SearchPartyDuplicateCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<SearchPartyDuplicateCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("SearchPartyDuplicate", "SearchPartyDuplicate", "Search party duplicate-name case type");

    SearchPartyField applicants =
        SearchPartyField.builder()
            .searchPartyCollectionFieldName("applicants")
            .searchPartyName("party.firstName,party.lastName")
            .build();
    SearchPartyField respondents =
        SearchPartyField.builder()
            .searchPartyCollectionFieldName("respondents")
            .searchPartyName("party.firstName,party.lastName")
            .build();

    builder.searchParty()
        .fields(List.of(applicants, respondents));
  }
}
