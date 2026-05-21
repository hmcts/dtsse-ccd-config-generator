package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.domain.model.callbacks.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.runtime.CcdCallbackExecutor;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Component
@Order(0)
@RequiredArgsConstructor
class SdkLegacyCallbackResolver implements LegacyCallbackResolver {

  private final ResolvedConfigRegistry registry;
  private final CcdCallbackExecutor executor;
  private final ObjectMapper mapper;

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
    public Optional<CallbackResponse> aboutToSubmit(CallbackRequest request) {
      if (eventConfig.getAboutToSubmitCallback() == null) {
        return Optional.empty();
      }
      return Optional.ofNullable(callbackResponse(executor.aboutToSubmit(request)));
    }

    @Override
    public Optional<SubmittedCallbackResponse> submitted(CallbackRequest request) {
      if (eventConfig.getSubmittedCallback() == null) {
        return Optional.empty();
      }
      return Optional.of(executor.submitted(request));
    }

    @Override
    public int submittedAttempts() {
      var retriesConfig = eventConfig.getRetries().get(Webhook.Submitted);
      return retriesConfig == null || retriesConfig.isEmpty() ? 1 : 3;
    }
  }

  private CallbackResponse callbackResponse(Object response) {
    return response == null ? new CallbackResponse() : mapper.convertValue(response, CallbackResponse.class);
  }
}
