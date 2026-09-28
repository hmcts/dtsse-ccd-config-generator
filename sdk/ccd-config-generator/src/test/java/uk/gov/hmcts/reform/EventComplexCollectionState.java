package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * States for {@link EventComplexCollectionCaseType}.
 */
public enum EventComplexCollectionState {
  @CCD(label = "Open state")
  Open,
  @CCD(label = "Submitted state")
  Submitted
}
