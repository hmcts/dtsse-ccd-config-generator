package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchCasesResultField<R extends HasRole> {
  private String id;
  private String label;
  private String listElementCode;
  private String displayContextParameter;
  private String resultsOrdering;
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
