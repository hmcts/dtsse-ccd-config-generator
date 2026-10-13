package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchCasesResultField {
  private String id;
  private String label;
  private String listElementCode;
  private String displayContextParameter;
  private String resultsOrdering;
  /**
   * Optional {@code AccessProfile}/{@code UserRole} scope for this result row. When unset the row is
   * emitted with an empty {@code UserRole}.
   */
  private HasRole userRole;
  /**
   * Optional {@code UseCase} for this result row. When unset the generator emits {@code orgcases}.
   */
  private String useCase;
}
