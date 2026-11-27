package uk.gov.hmcts.ccd.sdk;

/**
 * Runs inside every locally saved case event, system events included, after the case row is
 * written and before the event's audit snapshot is taken. Register one as a Spring bean.
 *
 * <p>A hook runs in the event's transaction, under the case lock: whatever it writes commits
 * only if the event does, and sees exactly the revision the event produced. A hook that throws
 * fails the event. Hooks read the case and write only their own tables; changing the case
 * belongs in the event's submit handler. Replayed (idempotent) submissions do not run hooks.
 */
@FunctionalInterface
public interface CaseEventHook {

  /** Called once per saved event. */
  void afterCaseEvent(CaseEventContext event);
}
