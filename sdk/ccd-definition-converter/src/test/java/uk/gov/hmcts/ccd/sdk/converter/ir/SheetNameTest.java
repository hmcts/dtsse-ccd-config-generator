package uk.gov.hmcts.ccd.sdk.converter.ir;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SheetNameTest {

  @Test
  void resolvesCanonicalNames() {
    assertThat(SheetName.forFileBaseName("CaseField")).contains(SheetName.CASE_FIELD);
    assertThat(SheetName.forFileBaseName("AuthorisationCaseEvent"))
        .contains(SheetName.AUTHORISATION_CASE_EVENT);
  }

  @Test
  void resolvesKnownAliases() {
    assertThat(SheetName.forFileBaseName("EventToComplexTypes"))
        .contains(SheetName.CASE_EVENT_TO_COMPLEX_TYPES);
    assertThat(SheetName.forFileBaseName("CaseEventToComplexTypes"))
        .contains(SheetName.CASE_EVENT_TO_COMPLEX_TYPES);
    assertThat(SheetName.forFileBaseName("SearchCaseResultFields"))
        .contains(SheetName.SEARCH_CASES_RESULT_FIELDS);
  }

  @Test
  void unknownNamesResolveEmpty() {
    assertThat(SheetName.forFileBaseName("NotASheet")).isEmpty();
  }
}
