package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchField<R extends HasRole> {
  protected String id;
  protected String label;
  protected String listElementCode;
  protected String showCondition;
  protected String displayContextParameter;
  protected R userRole;
  protected SortOrder order;
}
