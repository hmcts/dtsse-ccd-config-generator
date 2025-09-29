package uk.gov.hmcts.ccd.sdk.api.callback;

import uk.gov.hmcts.ccd.sdk.api.EventPayload;

@FunctionalInterface
public interface Start<T, S> {
  T start(EventPayload<T, S> payload);

}
