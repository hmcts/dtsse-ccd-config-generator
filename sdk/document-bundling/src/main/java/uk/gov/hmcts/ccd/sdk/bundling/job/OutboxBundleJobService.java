package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/**
 * The transactional-outbox implementation of {@link BundleJobService}, following
 * {@code sdk/task-management}'s {@code TaskOutboxService}.
 *
 * <p>Both submission shapes issue one plain {@code INSERT} through the consumer's JDBC template,
 * so the row joins whatever transaction is active in the caller — typically the consumer's
 * triggering CCD event — and a bundle request exists exactly when that change commits. A
 * repeated external id inserts nothing and returns the existing job, whatever state it is in; no
 * error, no second bundle. Nothing secret is ever persisted: no tokens, source bytes, or signed
 * URLs.
 *
 * <p>The supported isolation level for submitting transactions is {@code READ COMMITTED} (see
 * {@link BundleJobService}). Under {@code REPEATABLE READ} or {@code SERIALIZABLE} a concurrent
 * duplicate submission makes PostgreSQL abort the losing transaction with a serialization
 * failure; this service still honours the idempotency contract by reading the winner's committed
 * job on a fresh connection and returning it, but the caller's transaction remains aborted —
 * any later statement in it will fail.
 */
@Slf4j
public class OutboxBundleJobService implements BundleJobService {

  private static final int FIND_RETRIES_AFTER_CONFLICT = 5;
  private static final long FIND_RETRY_PAUSE_MILLIS = 20;

  private final BundleJobRepository repository;
  private final BundleJobJson json = new BundleJobJson();

  /**
   * Creates the service.
   *
   * @param repository the outbox repository
   */
  public OutboxBundleJobService(BundleJobRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
  }

  @Override
  public BundleJob submit(BundleRequest request, BundleExecutionContext context) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(context, "context must not be null");
    return submitInternal(request.externalId(), json.writeRequest(request),
        json.writeParameters(Map.of()), json.writeContext(context), true);
  }

  @Override
  public BundleJob submit(UUID externalId, Map<String, String> selectorParameters,
      BundleExecutionContext context) {
    Objects.requireNonNull(externalId, "externalId must not be null");
    Objects.requireNonNull(selectorParameters, "selectorParameters must not be null");
    Objects.requireNonNull(context, "context must not be null");
    return submitInternal(externalId, null, json.writeParameters(selectorParameters),
        json.writeContext(context), false);
  }

  @Override
  public Optional<BundleJob> find(UUID externalId) {
    Objects.requireNonNull(externalId, "externalId must not be null");
    return repository.find(externalId);
  }

  private BundleJob submitInternal(UUID externalId, String requestJson,
      String selectorParametersJson, String executionContextJson, boolean requestProvided) {
    try {
      boolean inserted = repository.insertIfAbsent(
          externalId, requestJson, selectorParametersJson, executionContextJson);
      if (!inserted) {
        log.warn("Bundle job {} was already submitted; this submission's content (request body "
            + "provided: {}) is discarded and the existing job returned", externalId,
            requestProvided);
      }
    } catch (ConcurrencyFailureException e) {
      // A concurrent duplicate submission under REPEATABLE READ/SERIALIZABLE: PostgreSQL aborts
      // the losing transaction (SQLSTATE 40001). Honour the idempotency contract by returning
      // the winner's committed job, read outside the (now unusable) caller transaction.
      log.warn("Bundle job {} lost a concurrent duplicate-submission race (the caller's "
          + "transaction is aborted if its isolation is above READ COMMITTED); returning the "
          + "existing job", externalId, e);
      return findCommittedAfterConflict(externalId, e);
    }
    return existing(externalId);
  }

  private BundleJob findCommittedAfterConflict(UUID externalId, RuntimeException conflict) {
    for (int attempt = 0; attempt < FIND_RETRIES_AFTER_CONFLICT; attempt++) {
      Optional<BundleJob> winner = repository.findOutsideCallerTransaction(externalId);
      if (winner.isPresent()) {
        return winner.get();
      }
      try {
        Thread.sleep(FIND_RETRY_PAUSE_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw conflict;
  }

  private BundleJob existing(UUID externalId) {
    return repository.find(externalId).orElseThrow(() -> new IllegalStateException(
        "Bundle job " + externalId + " was not visible immediately after submission"));
  }
}
