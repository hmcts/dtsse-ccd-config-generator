package uk.gov.hmcts.ccd.sdk.api.external;

import java.util.Set;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

/**
 * Configures an external event: one a bespoke frontend drives through CCD's API rather than
 * EXUI's event pages. It has a name, the roles that may use it and when EXUI offers it, but no
 * pages or fields; the frontend and the handlers exchange the payloads its {@link ExternalEventId}
 * declares instead of case data.
 */
public interface ExternalEventBuilder<T, R extends HasRole, S, O, I> {

  ExternalEventBuilder<T, R, S, O, I> name(String name);

  ExternalEventBuilder<T, R, S, O, I> description(String description);

  /** When EXUI offers the event on a case, as a CCD show condition. */
  ExternalEventBuilder<T, R, S, O, I> showCondition(String showCondition);

  ExternalEventBuilder<T, R, S, O, I> grant(Set<Permission> permissions, R... roles);

  /** Rejects submission with HTTP 409 if another event committed on the case after this one started. */
  ExternalEventBuilder<T, R, S, O, I> nonConcurrent();

  /**
   * Builds the payload the frontend is sent when it starts the event. Without one the event has no
   * about-to-start callback and the frontend is sent no payload.
   */
  ExternalEventBuilder<T, R, S, O, I> onStart(ExternalStartHandler<O> handler);
}
