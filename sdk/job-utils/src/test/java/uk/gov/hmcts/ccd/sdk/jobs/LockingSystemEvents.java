package uk.gov.hmcts.ccd.sdk.jobs;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.ActorAttribution;
import uk.gov.hmcts.ccd.sdk.SystemEventAction;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutionContext;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutionResult;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;
import uk.gov.hmcts.ccd.sdk.SystemEventResult;

/**
 * Keeps the system event contract the runner relies on, as the decentralised runtime's executor does: the action runs
 * in a transaction with the case locked, its result is recorded as the case's event, and a key already used replays.
 */
class LockingSystemEvents implements SystemEventExecutor {
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Set<UUID> used = ConcurrentHashMap.newKeySet();

  LockingSystemEvents(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public SystemEventExecutionResult execute(long caseReference, UUID idempotencyKey, SystemEventAction action) {
    if (used.contains(idempotencyKey)) {
      return new SystemEventExecutionResult(0, SystemEventExecutionResult.Outcome.REPLAYED);
    }
    tx.executeWithoutResult(status -> {
      Map<String, Object> locked = jdbc.queryForMap(
          "select case_type_id, state from ccd.case_data where reference = :case for update",
          Map.of("case", caseReference));
      SystemEventResult result = action.execute(new SystemEventExecutionContext(caseReference, idempotencyKey,
          (String) locked.get("case_type_id"), (String) locked.get("state")));
      jdbc.update("insert into events values (:case, :event, :summary)", Map.of("case", caseReference,
          "event", result.eventId(), "summary", result.summary().orElse("")));
    });
    used.add(idempotencyKey);
    return new SystemEventExecutionResult(1, SystemEventExecutionResult.Outcome.EXECUTED);
  }

  @Override
  public SystemEventExecutionResult execute(long caseReference, ActorAttribution actor, UUID idempotencyKey,
      SystemEventAction action) {
    return execute(caseReference, idempotencyKey, action);
  }
}
