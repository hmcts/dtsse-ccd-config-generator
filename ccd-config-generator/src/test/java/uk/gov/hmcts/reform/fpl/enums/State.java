package uk.gov.hmcts.reform.fpl.enums;

import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.BulkScan;

public enum State {
    @CCD(
      name = "Initial case state – create title as a minimum; add documents, etc.",
      access = {BulkScan.class}
    )
    Open,
    @CCD(
      name = "Submitted case state - LA can no longer edit",
      label = "# **${[CASE_REFERENCE]}** ${applicant1LastName} **&** " +
          "${applicant2LastName}\n" +
          "### **${[STATE]}**\n")
    Submitted,
    @CCD(name = "Gatekeeping case state - when send to gatekeeper event is triggered")
    Gatekeeping,
    @CCD(name = "State indicating that SDO is ready to send - triggered when SDO is issued")
    PREPARE_FOR_HEARING,
    @CCD(name = "Deleted case state - all data is removed")
    Deleted
}
