package uk.gov.hmcts.ccd.sdk.api;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class WorkBasketField<R> {
  protected String id;
  protected String label;
  protected String listElementCode;
  protected String showCondition;
  protected R userRole;
}
