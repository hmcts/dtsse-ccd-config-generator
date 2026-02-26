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
   *
   * @deprecated Use {@link #decentralisedEvent(String, Class, Submit)} with an isolated DTO class instead.
   */
  @Deprecated
  EventTypeBuilder<T, R, S> decentralisedEvent(String id, Submit<T, S> submitHandler);

  /**
   * Event that replaces AboutToSubmit/Submitted callbacks with the mandatory submitHandler
   * and AboutToStart with the provided startHandler.
   *
   * @deprecated Use {@link #decentralisedEvent(String, Class, Submit, Start)} with an isolated DTO class instead.
   */
  @Deprecated
  EventTypeBuilder<T, R, S> decentralisedEvent(String id, Submit<T, S> submitHandler, Start<T, S> startHandler);

  /**
   * Decentralised event using an isolated DTO class.
   * The DTO class defines the event's field schema. A CCD field prefix is auto-derived from the event ID.
   * DTO fields must be flat (primitives, enums, strings, dates, or SDK built-in types).
   */
  <D> EventTypeBuilder<D, R, S> decentralisedEvent(String id, Class<D> dtoClass, Submit<D, S> submitHandler);

  /**
   * Decentralised event using an isolated DTO class, with a start handler.
   * The DTO class defines the event's field schema. A CCD field prefix is auto-derived from the event ID.
   * DTO fields must be flat (primitives, enums, strings, dates, or SDK built-in types).
   */
  <D> EventTypeBuilder<D, R, S> decentralisedEvent(
      String id, Class<D> dtoClass, Submit<D, S> submitHandler, Start<D, S> startHandler);
}
