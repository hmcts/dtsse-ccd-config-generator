package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;

public class JsonPropertyStateCaseData {

  @CCD(label = "A field")
  private String caseField;
}
