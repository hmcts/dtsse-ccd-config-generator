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
}
