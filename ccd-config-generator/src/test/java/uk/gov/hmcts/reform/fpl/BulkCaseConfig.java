package uk.gov.hmcts.reform.fpl;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;


import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;


public class BulkCaseConfig implements uk.gov.hmcts.ccd.sdk.api.CCDConfig<BulkCaseConfig.BulkCase, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<BulkCase, State, UserRole> builder) {
    // Duplicates event on the main CaseData class.
    // Callback handler should route correctly based on case type.
    builder.event("addFamilyManCaseNumber")
        .forAllStates()
        .name("Add case number")
        .explicitGrants()
        .grant(CRU, HMCTS_ADMIN)
        .aboutToStartCallback(this::aboutToStart)
        .fields()
        .page("1")
        .optional(BulkCase::getFamilyManCaseNumber)
        .optional(BulkCase::getCaseNotes);

  }

  private AboutToStartOrSubmitResponse<BulkCase, State> aboutToStart(
      CaseDetails<BulkCase, State> bulkCaseStateCaseDetails) {
    return null;
  }

  @Data
  static class BulkCase {
    private String familyManCaseNumber;
    private String caseNotes;
  }
}
