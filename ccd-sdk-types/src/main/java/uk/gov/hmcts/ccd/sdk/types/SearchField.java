package uk.gov.hmcts.ccd.sdk.types;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchField {
  private String id;
  private String label;
}
