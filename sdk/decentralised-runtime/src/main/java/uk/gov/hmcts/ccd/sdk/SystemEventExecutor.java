package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;

/**
 * Executes local case changes transactionally with CCD history and database auditing.
 */
public interface SystemEventExecutor {

  void execute(
      long caseReference,
      UUID idempotencyKey,
      SystemEventAction action
  );

  void execute(
      long caseReference,
      ActorAttribution actor,
      UUID idempotencyKey,
      SystemEventAction action
  );
}
