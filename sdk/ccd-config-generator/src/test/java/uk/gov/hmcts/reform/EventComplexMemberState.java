package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * States for {@link EventComplexMemberCaseType}.
 */
public enum EventComplexMemberState {
  @CCD(label = "Open state")
  Open,
  @CCD(label = "Submitted state")
  Submitted
}
