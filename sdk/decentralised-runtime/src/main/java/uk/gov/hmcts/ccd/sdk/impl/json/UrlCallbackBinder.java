package uk.gov.hmcts.ccd.sdk.impl.json;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Webhook;

/**
 * Binds an event's about-to-submit and submitted callbacks given by URL to handlers that call that
 * URL.
 *
 * <p>For a decentralised case type CCD hands the submission to this service, which runs those two
 * hooks itself and only knows how to run a handler. The data store still calls the about-to-start and
 * mid-event URLs, so they need nothing here. The URL stays on the event, so the generated definition
 * is unchanged.
 */
@Component
@RequiredArgsConstructor
class UrlCallbackBinder {

  private final ResolvedConfigRegistry registry;
  private final JsonCallbackBridge bridge;

  @PostConstruct
  @SuppressWarnings({"rawtypes", "unchecked"})
  void bind() {
    for (var config : registry.getAll()) {
      for (Event event : config.getEvents().values()) {
        String aboutToSubmit = (String) event.getCallbackUrls().get(Webhook.AboutToSubmit);
        if (aboutToSubmit != null && event.getAboutToSubmitCallback() == null) {
          bridge.validate(aboutToSubmit);
          event.setAboutToSubmitCallback(bridge.aboutToSubmit(aboutToSubmit, event.getId()));
        }
        String submitted = (String) event.getCallbackUrls().get(Webhook.Submitted);
        if (submitted != null && event.getSubmittedCallback() == null) {
          bridge.validate(submitted);
          event.setSubmittedCallback(bridge.submitted(submitted, event.getId()));
        }
      }
    }
  }
}
