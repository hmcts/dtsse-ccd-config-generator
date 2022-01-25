package uk.gov.hmcts.ccd.sdk.api;

public interface BulkScanEventTypeBuilder<T, R extends HasRole, S> {
  Event.EventBuilder<T, R, S> forAllStates();
}
