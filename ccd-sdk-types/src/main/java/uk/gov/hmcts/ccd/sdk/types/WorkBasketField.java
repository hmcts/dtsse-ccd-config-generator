package uk.gov.hmcts.ccd.sdk.types;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WorkBasketField {
  private String id;
  private String label;
  private String sortingInformation;
}
