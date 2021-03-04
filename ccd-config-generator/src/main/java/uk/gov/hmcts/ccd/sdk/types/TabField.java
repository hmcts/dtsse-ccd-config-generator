package uk.gov.hmcts.ccd.sdk.types;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class TabField {

  private String id;
  private String showCondition;
}
