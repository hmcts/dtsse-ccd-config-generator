package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * States for {@link EventColumnsCaseType}.
 */
public enum EventColumnsState {
  @CCD(label = "Open state")
  Open,
  @CCD(label = "Closed state")
  Closed
}
