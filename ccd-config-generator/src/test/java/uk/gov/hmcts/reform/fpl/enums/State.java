package uk.gov.hmcts.reform.fpl.enums;

import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.BulkScan;

public enum State {
    @CCD(
      label = "Initial case state – create title as a minimum; add documents, etc.",
      access = {BulkScan.class}
    )
    Open,
    @CCD(
      label = "Submitted case state - LA can no longer edit",
      hint = "# **${[CASE_REFERENCE]}** ${applicant1LastName} **&** " +
          "${applicant2LastName}\n" +
          "### **${[STATE]}**\n")
    Submitted,
    @CCD(label = "Gatekeeping case state - when send to gatekeeper event is triggered")
    Gatekeeping,
    @CCD(label = "State indicating that SDO is ready to send - triggered when SDO is issued")
    PREPARE_FOR_HEARING,
    @CCD(label = "Deleted case state - all data is removed")
    Deleted
}
