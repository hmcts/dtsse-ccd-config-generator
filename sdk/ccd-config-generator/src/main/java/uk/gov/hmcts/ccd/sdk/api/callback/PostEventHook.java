package uk.gov.hmcts.ccd.sdk.api.callback;

import uk.gov.hmcts.ccd.sdk.api.CaseDetails;

/**
 * Runs after an event's about-to-submit handler. On a case type that is not decentralised it runs
 * for every event whose about-to-submit callback the SDK serves. On a decentralised case type it
 * runs for every submission of an event built with {@code event(...)}, including one with no
 * about-to-submit callback, but not for events built with {@code decentralisedEvent(...)} or
 * {@code externalEvent(...)}.
 *
 * @see uk.gov.hmcts.ccd.sdk.api.ConfigBuilder#addPostEventHook(PostEventHook)
 */
@FunctionalInterface
public interface PostEventHook<T, S> {

  /**
   * Inspects or replaces the response the event's about-to-submit handler produced.
   *
   * @param eventId the ID of the event being submitted
   * @param details the case details as they were passed to the handler; the handler may have
   *     modified them
   * @param detailsBefore the case details before the event, or {@code null} when CCD sends none
   * @param response the handler's response, or the previous hook's when several are registered;
   *     never null. It may carry errors. For an event with no about-to-submit handler it carries
   *     the event's case data and no state, and the details carry the state the event ends in
   * @return the response to pass to the next hook, or to return to CCD after the last one; must
   *     not be null
   */
  AboutToStartOrSubmitResponse<T, S> handle(String eventId, CaseDetails<T, S> details,
                                            CaseDetails<T, S> detailsBefore,
                                            AboutToStartOrSubmitResponse<T, S> response);
}
