package uk.gov.hmcts.ccd.sdk.converter.model;

import lombok.Builder;
import lombok.Value;

/**
 * One row of a search-related sheet (SearchInputFields, SearchResultFields,
 * WorkBasketInputFields, WorkBasketResultFields, SearchCasesResultFields), mapped onto the
 * corresponding ConfigBuilder search builder.
 */
@Value
@Builder
public class SearchFieldModel {

  String caseFieldId;
  String label;
  Integer displayOrder;
  String displayContextParameter;

  /** ListElementCode for searching within a complex field, where present. */
  String listElementCode;

  /** FieldShowCondition — search/workbasket input sheets only, emitted via the per-field lambda. */
  String showCondition;

  /** SearchCasesResultFields only. */
  String useCase;

  /**
   * ResultsOrdering (e.g. {@code "1:ASC"}) on the result sheets, emitted via the per-field lambda's
   * {@code resultsOrdering(SortOrder)} for the four SearchBuilder sheets.
   */
  String resultsOrdering;

  /** Role restriction (UserRole column), or null. */
  String userRole;
}
