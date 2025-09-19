package uk.gov.hmcts.reform.fpl;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;


import lombok.Data;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

@Component
public class BulkCaseConfig implements uk.gov.hmcts.ccd.sdk.api.CCDConfig<BulkCaseConfig.BulkCase, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<BulkCase, State, UserRole> builder) {
    builder.caseType("bulk", "Bulk case", "Bulk case desc");
    // Duplicates event on the main CaseData class.
    // Callback handler should route correctly based on case type.
    builder.event("addFamilyManCaseNumber")
        .forAllStates()
        .name("Add case number")
        .grant(CRU, HMCTS_ADMIN)
        .aboutToStartCallback(this::aboutToStart)
        .fields()
        .page("1")
        .optional(BulkCase::getFamilyManCaseNumber)
        .optional(BulkCase::getCaseNotes);

  }

  private AboutToStartOrSubmitResponse<BulkCase, State> aboutToStart(
      CaseDetails<BulkCase, State> details) {
    details.getData().setFamilyManCaseNumber("bulk-about-to-start");
    return AboutToStartOrSubmitResponse.<BulkCase, State>builder()
        .data(details.getData())
        .build();
  }

  @Data
  public static class BulkCase {
    private String familyManCaseNumber;
    private String caseNotes;
  }
}
