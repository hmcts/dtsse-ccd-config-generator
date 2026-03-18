package uk.gov.hmcts.ccd.sdk;

import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

import java.util.List;

/**
 * Defines a callback handler used by services that submit events through JSON-based CCD definitions.
 *
 * <p>The handler is parameterised by the service's case data type(s) and is to be used by decentralised services
 * as an alternative to dedicated {@code aboutToSubmit} and {@code submitted} controller endpoints, or to existing
 * callback handler implementations.
 */
public interface CallbackHandler<T> {

  /**
   * Returns the case type IDs handled by this callback.
   *
   * <p>This list must return at least one element, and no elements can be an empty string,
   * else an IllegalStateException will be thrown on application startup.
   *
   * @return list of handled case type IDs
   */
  List<String> getHandledCaseTypeIds();

  /**
   * Returns the event IDs handled by this callback.
   *
   * <p>This list must return at least one element, and no elements can be an empty string,
   * else an IllegalStateException will be thrown on application startup.
   *
   * @return list of handled event IDs
   */
  List<String> getHandledEventIds();

  /**
   * Invoked during the about-to-submit callback phase.
   *
   * <p>This method provides a default implementation so handlers only need to override it when they
   * actively participate in this phase. If {@link #acceptsAboutToSubmit()} returns {@code false},
   * the dispatcher will skip invocation entirely.
   *
   * @param data callback request payload
   * @return callback response, or {@code null} when not used
   */
  default CallbackResponse<T> aboutToSubmit(CallbackRequest data) {
    return null;
  }

  /**
   * Indicates whether this handler should be invoked for the about-to-submit phase when its binding
   * matches the incoming request.
   *
   * @return {@code true} to invoke about-to-submit, otherwise {@code false}
   */
  boolean acceptsAboutToSubmit();

  /**
   * Invoked during the submitted callback phase.
   *
   * <p>This method provides a default implementation so handlers only need to override it when they
   * actively participate in this phase. If {@link #acceptsSubmitted()} returns {@code false}, the
   * dispatcher will skip invocation entirely. Submitted callbacks are executed after the database
   * transaction has completed.
   *
   * @param data callback request payload
   * @return submitted callback response, or {@code null} when not used
   */
  default SubmittedCallbackResponse submitted(CallbackRequest data) {
    return null;
  }

  /**
   * Indicates whether this handler should be invoked for the submitted phase when its binding
   * matches the incoming request.
   *
   * @return {@code true} to invoke submitted, otherwise {@code false}
   */
  boolean acceptsSubmitted();

  /**
   * Indicates whether submitted callbacks should be retried when an exception is thrown.
   *
   * <p>This mirrors current CCD behaviour, where submitted callbacks are effectively either not
   * retried or retried up to three attempts, regardless of the requested CCD retry count.
   *
   * @return {@code true} if submitted callbacks should be retried, otherwise {@code false}
   */
  default boolean shouldRetrySubmitted() {
    return false;
  }
}
