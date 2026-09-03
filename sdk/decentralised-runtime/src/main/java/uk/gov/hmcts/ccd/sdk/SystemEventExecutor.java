package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;

/**
 * Executes local case changes transactionally with CCD history and database auditing.
 */
public interface SystemEventExecutor {

  <State extends Enum<State>> void execute(
      long caseReference,
      UUID idempotencyKey,
      SystemEventAction<State> action
  );

  <State extends Enum<State>> void execute(
      long caseReference,
      ActorAttribution actor,
      UUID idempotencyKey,
      SystemEventAction<State> action
  );
}
