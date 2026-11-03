package uk.gov.hmcts.ccd.sdk.api.callback;

import uk.gov.hmcts.ccd.sdk.api.CaseDetails;

/**
 * Runs after an event's about-to-submit handler, for every event of the case type whose
 * about-to-submit callback the SDK serves.
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
   * @param response the handler's response, or the previous hook's when several are registered.
   *     It may carry errors
   * @return the response to pass to the next hook, or to return to CCD after the last one
   */
  AboutToStartOrSubmitResponse<T, S> handle(String eventId, CaseDetails<T, S> details,
                                            CaseDetails<T, S> detailsBefore,
                                            AboutToStartOrSubmitResponse<T, S> response);
}
