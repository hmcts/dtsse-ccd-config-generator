package uk.gov.hmcts.ccd.sdk.types;

public interface WebhookConvention {
    String buildUrl(Webhook webhook, String eventId);
}
