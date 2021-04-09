package uk.gov.hmcts.reform.fpl.event;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;


import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.AboutToStartOrSubmitCallbackResponse;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

@Component
public class AddCaseNumber implements CCDConfig<CaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
    builder.event("addFamilyManCaseNumber")
        .forAllStates()
        .name("Add case number")
        .explicitGrants()
        .grant(CRU, HMCTS_ADMIN)
        .aboutToSubmitWebhook("add-case-number")
        .submittedWebhook()
        .fields()
        .optional(CaseData::getFamilyManCaseNumber);
  }

  private AboutToStartOrSubmitCallbackResponse<CaseData, State> aboutToSubmit(
      CaseDetails<CaseData, State> details) {
    CaseData d = details.getData();
    d.setFamilyManCaseNumber("12345");

    return AboutToStartOrSubmitCallbackResponse.<CaseData, State>builder()
        .data(d)
        .build();
  }
}
