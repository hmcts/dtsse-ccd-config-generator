package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class TabField {

  private String id;
  private String showCondition;
  private String displayContextParameter;
  private String label;
}
