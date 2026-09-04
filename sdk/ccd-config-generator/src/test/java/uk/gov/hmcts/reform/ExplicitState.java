package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;

public enum ExplicitState {
  @CCD(label = "Open state")
  Open,
  @CCD(label = "Submitted state")
  Submitted
}
