package uk.gov.hmcts.reform.fpl.event;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;


import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;
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
        .ttlIncrement(100)
        .grant(CRU, HMCTS_ADMIN)
        .aboutToStartCallback(this::aboutToStart)
        .aboutToSubmitCallback(this::aboutToSubmit)
        .submittedCallback(this::submitted)
        .fields()
        .page("1", this::setNumber)
        .optional(CaseData::getFamilyManCaseNumber)
        .optional(CaseData::getCaseNotes);
  }

  private AboutToStartOrSubmitResponse<CaseData, State> setNumber(
      CaseDetails<CaseData, State> details,
      CaseDetails<CaseData, State> detailsBefore) {
    CaseData data = details.getData();
    data.setFamilyManCaseNumber("PLACEHOLDER");
    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .data(data)
        .build();
  }

  private SubmittedCallbackResponse submitted(CaseDetails<CaseData, State> caseDataStateCaseDetails,
                                              CaseDetails<CaseData, State> caseDataStateCaseDetails1) {
    return SubmittedCallbackResponse.builder().build();
  }

  private AboutToStartOrSubmitResponse<CaseData, State> aboutToStart(
      CaseDetails<CaseData, State> details) {
    CaseData data = details.getData();
    data.setFamilyManCaseNumber("start-12345");
    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .data(data)
        .build();
  }

  private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
      CaseDetails<CaseData, State> details,
    CaseDetails<CaseData, State> detailsBefore) {
      CaseData d = details.getData();
    d.setFamilyManCaseNumber("12345");

    if (null != d.getRetiredFields().getOrderAppliesToAllChildren()) {
      throw new RuntimeException("Retired field set");
    }
    if (null == d.getCaseLocalAuthority()) {
      throw new RuntimeException("Migration did not run");
    }

    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .data(d)
        .build();
  }
}
