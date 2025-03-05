package uk.gov.hmcts.ccd.sdk;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.runtime.CallbackController;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

@RequiredArgsConstructor
@Configuration
public class ConfigGeneratorCallbackDispatcher implements CCDEventListener {

    private final CallbackController controller;

    public boolean hasAboutToSubmitCallbackForEvent(String caseType, String event) {
        return controller.hasAboutToSubmitCallback(caseType, event);
    }

  @Override
  public AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request) {
    return controller.aboutToSubmit(request);
  }

  @Override
  public boolean hasSubmittedCallbackForEvent(String caseType, String event) {
        return controller.hasSubmittedCallback(caseType, event);
    }
}
