package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;

/**
 * Executes local case changes transactionally with CCD history and database auditing.
 */
public interface SystemEventExecutor {

  SystemEventExecutionResult execute(
      long caseReference,
      UUID idempotencyKey,
      SystemEventAction action
  );

  SystemEventExecutionResult execute(
      long caseReference,
      ActorAttribution actor,
      UUID idempotencyKey,
      SystemEventAction action
  );
}
