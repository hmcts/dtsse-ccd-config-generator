package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * State enum exercising {@code @CCD#description()}. {@code CaseManagement} carries an explicit
 * {@code description}, so its {@code Description} column must differ from {@code Name}; {@code Open}
 * has none, so its {@code Description} must still default to {@code Name}, unchanged.
 */
public enum StateDescriptionState {
  @CCD(label = "Open state")
  Open,

  @CCD(label = "Case management state", description = "The case is under active case management")
  CaseManagement
}
