package uk.gov.hmcts.ccd.sdk.api;

import uk.gov.hmcts.ccd.sdk.api.callback.Start;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventId;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventStates;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitHandler;

/**
 * Builder interface for decentralised CCD configurations.
 * Extends the base ConfigBuilder and exposes decentralised-only APIs.
 */
public interface DecentralisedConfigBuilder<T, S, R extends HasRole> extends ConfigBuilder<T, S, R> {

  /**
   * The case field that carries an external event's payload, as a JSON string, between the
   * frontend and the handlers. The SDK defines it for every case type with external events; it is
   * never written to the case.
   */
  String PAYLOAD_FIELD = "sdkEventPayload";

  /**
   * Event that replaces AboutToSubmit/Submitted callbacks
   * with the mandatory submitHandler.
   */
  EventTypeBuilder<T, R, S> decentralisedEvent(String id, Submit<T, S> submitHandler);

  /**
   * Event that replaces AboutToSubmit/Submitted callbacks with the mandatory submitHandler
   * and AboutToStart with the provided startHandler.
   */
  EventTypeBuilder<T, R, S> decentralisedEvent(String id, Submit<T, S> submitHandler, Start<T, S> startHandler);

  /**
   * External event: one a bespoke frontend, such as a citizen or judicial journey, drives through
   * CCD's API instead of EXUI's event pages. {@code id} declares its id and the types it exchanges
   * with the frontend in {@link #PAYLOAD_FIELD}: the payload the frontend submits is deserialised as
   * the submit type for {@code submitHandler}, and an optional start handler builds the start type
   * the frontend is sent when it starts the event. Like other decentralised events, submission does
   * not change the case data.
   */
  <O, I> ExternalEventStates<T, R, S, O, I> externalEvent(ExternalEventId<O, I> id,
                                                   ExternalSubmitHandler<S, I> submitHandler);
}
