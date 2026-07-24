package uk.gov.hmcts.ccd.sdk.converter.ir;

import java.util.Arrays;
import java.util.Optional;

/**
 * The canonical CCD definition sheets, as understood by ccd-definition-store-api's importer
 * and produced as JSON files (or fragment directories) by service teams.
 *
 * <p>Transcribed from ccd-definition-store-api
 * {@code excel-importer/.../util/mapper/SheetName.java}. Aliases cover the naming drift seen
 * in real definitions: the definition store calls the event-to-complex-types sheet
 * {@code EventToComplexTypes} while the ccd-definition-processor template and most services
 * name the file/directory {@code CaseEventToComplexTypes}.
 */
public enum SheetName {
  JURISDICTION("Jurisdiction"),
  CASE_TYPE("CaseType"),
  CASE_FIELD("CaseField"),
  COMPLEX_TYPES("ComplexTypes"),
  FIXED_LISTS("FixedLists"),
  STATE("State"),
  CASE_EVENT("CaseEvent"),
  CASE_EVENT_TO_FIELDS("CaseEventToFields"),
  CASE_EVENT_TO_COMPLEX_TYPES("EventToComplexTypes", "CaseEventToComplexTypes"),
  CASE_TYPE_TAB("CaseTypeTab"),
  CASE_ROLE("CaseRoles"),
  WORK_BASKET_INPUT_FIELD("WorkBasketInputFields"),
  WORK_BASKET_RESULT_FIELDS("WorkBasketResultFields"),
  SEARCH_INPUT_FIELD("SearchInputFields"),
  SEARCH_RESULT_FIELD("SearchResultFields"),
  SEARCH_CASES_RESULT_FIELDS("SearchCasesResultFields", "SearchCaseResultFields"),
  USER_PROFILE("UserProfile"),
  AUTHORISATION_CASE_TYPE("AuthorisationCaseType"),
  AUTHORISATION_CASE_FIELD("AuthorisationCaseField"),
  AUTHORISATION_CASE_EVENT("AuthorisationCaseEvent"),
  AUTHORISATION_CASE_STATE("AuthorisationCaseState"),
  AUTHORISATION_COMPLEX_TYPE("AuthorisationComplexType"),
  SEARCH_ALIAS("SearchAlias"),
  BANNER("Banner"),
  CHALLENGE_QUESTION("ChallengeQuestion"),
  ROLE_TO_ACCESS_PROFILES("RoleToAccessProfiles"),
  ACCESS_TYPE("AccessType"),
  ACCESS_TYPE_ROLE("AccessTypeRole"),
  SEARCH_PARTY("SearchParty"),
  SEARCH_CRITERIA("SearchCriteria"),
  CATEGORY("Categories");

  private final String sheetName;
  private final String[] aliases;

  SheetName(String sheetName, String... aliases) {
    this.sheetName = sheetName;
    this.aliases = aliases;
  }

  public String getName() {
    return sheetName;
  }

  /**
   * Resolves a sheet from a file or directory base name (no {@code .json} extension,
   * no overlay suffix), accepting known aliases.
   *
   * @param baseName the file or directory base name
   * @return the matching sheet, or empty if the name is not a known sheet
   */
  public static Optional<SheetName> forFileBaseName(String baseName) {
    return Arrays.stream(values())
        .filter(s -> s.sheetName.equals(baseName) || Arrays.asList(s.aliases).contains(baseName))
        .findFirst();
  }
}
