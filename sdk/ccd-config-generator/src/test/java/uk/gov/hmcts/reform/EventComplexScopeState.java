package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * States for {@link EventComplexScopeCaseType}.
 */
public enum EventComplexScopeState {
  @CCD(label = "Open state")
  Open,
  @CCD(label = "Submitted state")
  Submitted
}
