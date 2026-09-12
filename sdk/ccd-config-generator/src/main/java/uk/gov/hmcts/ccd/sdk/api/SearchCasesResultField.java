package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchCasesResultField<R extends HasRole> {
  private String id;
  private String label;
  private String listElementCode;
  /**
   * Legacy positional carriers written by the long-standing {@code field(id, label, displayContext,
   * listElementCode, resultsOrdering)} overloads. The generator renders these the historic (mis-wired)
   * way — the column is gated on the carrier's presence but emits the {@code ListElementCode} value —
   * so every existing consumer that uses the positional API regenerates byte-identically. Configs that
   * want the real column value use the fluent {@link SearchCases.ResultFieldBuilder} carriers below.
   */
  private String displayContextParameter;
  private String resultsOrdering;
  /**
   * Fluent opt-in carriers populated only by the {@code field(id, label, Consumer)} lambda overload.
   * The generator writes their actual value into the {@code DisplayContextParameter}/
   * {@code ResultsOrdering} columns, so the lambda path emits the correct value while a config that
   * never uses it leaves these null and is unchanged.
   */
  private String fluentDisplayContextParameter;
  private String fluentResultsOrdering;
  /**
   * Optional {@code AccessProfile}/{@code UserRole} scope for this result row. When unset the row is
   * emitted with the historic empty {@code UserRole}, so unscoped configurations are unchanged.
   */
  private R userRole;
  /**
   * Optional {@code UseCase} for this result row. When unset the generator falls back to the historic
   * {@code orgcases} value, so configurations that do not set it are unchanged.
   */
  private String useCase;
}
