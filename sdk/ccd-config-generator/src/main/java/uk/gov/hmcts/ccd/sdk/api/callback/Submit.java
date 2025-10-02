package uk.gov.hmcts.ccd.sdk.api.callback;

import uk.gov.hmcts.ccd.sdk.api.EventPayload;

@FunctionalInterface
public interface Submit<T, S> {
  SubmitResponse submit(EventPayload<T, S> payload);

}
