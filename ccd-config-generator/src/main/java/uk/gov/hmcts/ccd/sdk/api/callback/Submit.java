package uk.gov.hmcts.ccd.sdk.api.callback;

import java.io.Serializable;

import uk.gov.hmcts.ccd.sdk.api.EventPayload;

@FunctionalInterface
public interface Submit<T, S> extends Serializable {
  void submit(EventPayload<T, S> payload);

}
