package uk.gov.hmcts.test.library;

import com.google.common.collect.SetMultimap;
import uk.gov.hmcts.ccd.sdk.api.*;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;

@Component
public class CCDConfig implements uk.gov.hmcts.ccd.sdk.api.CCDConfig<CaseData, State, Role> {
  @Override
  public void configure(ConfigBuilder<CaseData, State, Role> builder) {
    builder.caseType("test", "test", "test");
    builder
      .event("submit-case")
      .forAllStates()
      .grant(CRU, Role.Foo)
      .name("Submit case")
      .description("Submit case")
      .aboutToSubmitCallback(this::aboutToSubmit)
      .submittedCallback(this::submitted)
      .fields()
      .mandatory(CaseData::getDocuments)
      .done();

    new HasAccessControl() {
      @Override
      public SetMultimap<HasRole, Permission> getGrants() {
        return null;
      }
    };
  }

  private uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse submitted(CaseDetails<CaseData, State> caseDataStateCaseDetails, CaseDetails<CaseData, State> caseDataStateCaseDetails1) {
    return null;
  }

  private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(CaseDetails<CaseData, State> caseDataStateCaseDetails, CaseDetails<CaseData, State> caseDataStateCaseDetails1) {
    return null;
  }
}
