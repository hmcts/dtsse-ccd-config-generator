package uk.gov.hmcts.ccd.sdk.api;

import uk.gov.hmcts.ccd.sdk.api.callback.Start;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;

/**
 * Builder interface for decentralised CCD configurations.
 * Extends the base ConfigBuilder and exposes decentralised-only APIs.
 */
public interface DecentralisedConfigBuilder<T, S, R extends HasRole> extends ConfigBuilder<T, S, R> {

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
   * Service event using an isolated DTO class.
   * The DTO is serialised as a JSON payload in a single opaque CCD field.
   */
  <D> EventTypeBuilder<D, R, S> serviceEvent(
      String id, Class<D> dtoClass, Submit<D, S> submitHandler);

  /**
   * Service event using an isolated DTO class, with a start handler.
   * The DTO is serialised as a JSON payload in a single opaque CCD field.
   */
  <D> EventTypeBuilder<D, R, S> serviceEvent(
      String id, Class<D> dtoClass, Submit<D, S> submitHandler, Start<D, S> startHandler);
}
