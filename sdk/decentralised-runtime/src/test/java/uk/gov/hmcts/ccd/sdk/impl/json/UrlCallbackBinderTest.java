package uk.gov.hmcts.ccd.sdk.impl.json;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;

@SuppressWarnings({"rawtypes", "unchecked"})
class UrlCallbackBinderTest {

  private static final String ABOUT_TO_SUBMIT_URL = "${SERVICE_URL}/about-to-submit";
  private static final String SUBMITTED_URL = "${SERVICE_URL}/submitted";

  private final ResolvedConfigRegistry registry = mock(ResolvedConfigRegistry.class);
  private final JsonCallbackBridge bridge = mock(JsonCallbackBridge.class);

  @Test
  void bindsSubmitHooksGivenByUrlToHandlersCallingThatUrl() {
    final Event event = event(Map.of(
        Webhook.AboutToStart, "${SERVICE_URL}/about-to-start",
        Webhook.AboutToSubmit, ABOUT_TO_SUBMIT_URL,
        Webhook.Submitted, SUBMITTED_URL));
    AboutToSubmit aboutToSubmit = mock(AboutToSubmit.class);
    Submitted submitted = mock(Submitted.class);
    when(bridge.aboutToSubmit(ABOUT_TO_SUBMIT_URL, "urlEvent")).thenReturn(aboutToSubmit);
    when(bridge.submitted(SUBMITTED_URL, "urlEvent")).thenReturn(submitted);

    new UrlCallbackBinder(registry, bridge).bind();

    verify(bridge).validate(ABOUT_TO_SUBMIT_URL);
    verify(bridge).validate(SUBMITTED_URL);
    verify(event).setAboutToSubmitCallback(aboutToSubmit);
    verify(event).setSubmittedCallback(submitted);
  }

  @Test
  void leavesAnEventWithoutCallbackUrlsAlone() {
    Event event = event(Map.of());

    new UrlCallbackBinder(registry, bridge).bind();

    verify(event, never()).setAboutToSubmitCallback(any());
    verify(event, never()).setSubmittedCallback(any());
  }

  private Event event(Map<Webhook, String> callbackUrls) {
    Event event = mock(Event.class);
    when(event.getId()).thenReturn("urlEvent");
    when(event.getCallbackUrls()).thenReturn(callbackUrls);
    ResolvedCCDConfig config = mock(ResolvedCCDConfig.class);
    when(config.getEvents()).thenReturn(ImmutableMap.of("urlEvent", event));
    when(registry.getAll()).thenReturn(List.of(config));
    return event;
  }
}
