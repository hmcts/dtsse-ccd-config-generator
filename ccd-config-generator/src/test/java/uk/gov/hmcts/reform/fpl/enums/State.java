package uk.gov.hmcts.reform.fpl.enums;

import uk.gov.hmcts.ccd.sdk.api.CCD;

public enum State {
    @CCD(label = "Initial case state – create title as a minimum; add documents, etc.")
    Open,
    @CCD(label = "Submitted case state - LA can no longer edit")
    Submitted,
    @CCD(label = "Gatekeeping case state - when send to gatekeeper event is triggered")
    Gatekeeping,
    @CCD(name = "Prepare for hearing",
        label = "State indicating that SDO is ready to send - triggered when SDO is issued")
    PREPARE_FOR_HEARING,
    @CCD(label = "Deleted case state - all data is removed")
    Deleted
}
