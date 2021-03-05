package uk.gov.hmcts.ccd.sdk.api;

public interface WebhookConvention {

  String buildUrl(Webhook webhook, String eventId);
}
