package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.runtime.CcdCallbackExecutor;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Component
@Order(0)
@RequiredArgsConstructor
class SdkLegacyCallbackDispatcher implements LegacyCallbackDispatcher {

  private final ResolvedConfigRegistry registry;
  private final CcdCallbackExecutor executor;
  private final LegacyCallbackResponseAdapter responseAdapter;

  @Override
  public Optional<LegacyCallback> resolve(String caseTypeId, String eventId) {
    Optional<Event<?, ?, ?>> event = registry.find(caseTypeId)
        .map(config -> config.getEvents().get(eventId));

    return event
        .filter(eventConfig -> eventConfig.getAboutToSubmitCallback() != null
            || eventConfig.getSubmittedCallback() != null)
        .map(SdkLegacyCallback::new);
  }

  private class SdkLegacyCallback implements LegacyCallback {

    private final Event<?, ?, ?> eventConfig;

    private SdkLegacyCallback(Event<?, ?, ?> eventConfig) {
      this.eventConfig = eventConfig;
    }

    @Override
    public Optional<LegacyAboutToSubmitCallbackResponse> aboutToSubmit(CallbackRequest request,
                                                                       String authorisation) {
      if (eventConfig.getAboutToSubmitCallback() == null) {
        return Optional.empty();
      }
      return Optional.of(responseAdapter.aboutToSubmit(executor.aboutToSubmit(request)));
    }

    @Override
    public Optional<SubmittedCallbackResponse> submitted(CallbackRequest request, String authorisation) {
      if (eventConfig.getSubmittedCallback() == null) {
        return Optional.empty();
      }
      return Optional.of(responseAdapter.submitted(executor.submitted(request)));
    }

    @Override
    public int submittedAttempts() {
      var retriesConfig = eventConfig.getRetries().get(Webhook.Submitted);
      return retriesConfig == null || retriesConfig.isEmpty() ? 1 : 3;
    }
  }
}
