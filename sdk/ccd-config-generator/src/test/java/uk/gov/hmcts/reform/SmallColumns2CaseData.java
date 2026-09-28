package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
public class SmallColumns2CaseData {

  @CCD(label = "A field")
  private String aField;
}
