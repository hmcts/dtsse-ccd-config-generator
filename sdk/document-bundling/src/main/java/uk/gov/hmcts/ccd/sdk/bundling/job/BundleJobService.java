package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/**
 * Durable, asynchronous bundle execution over a transactional outbox in the consuming service's
 * own database.
 *
 * <p>Submission inserts an outbox row in the caller's current transaction, so a bundle request
 * exists exactly when the triggering change commits. The consumer-minted external id is the
 * idempotency key: a repeated submission with the same id returns the existing active or
 * completed job instead of creating another bundle. Neither submission shape holds an HTTP or CCD
 * callback open; the caller records the job id and returns.
 *
 * <p><strong>Transaction isolation.</strong> The supported isolation level for a submitting
 * transaction is {@code READ COMMITTED} (PostgreSQL's default), under which concurrent duplicate
 * submissions resolve natively: the loser's insert skips and the existing job is returned, with
 * the caller's transaction intact. Under {@code REPEATABLE READ} or {@code SERIALIZABLE},
 * PostgreSQL aborts the losing transaction with a serialization failure (SQLSTATE 40001); the
 * outbox implementation still returns the winner's committed job, but the caller's transaction
 * is already doomed — any later statement in it fails, and its commit becomes a rollback.
 */
public interface BundleJobService {

  /**
   * Submits a fully built request: generation is a snapshot at submission unless an overriding
   * {@link BundleDocumentSelector} recompiles it at execution.
   *
   * @param request the bundle to generate; its external id is the idempotency key
   * @param context the non-secret consumer context persisted with the job
   * @return the queued job, or the existing job for a repeated external id
   */
  BundleJob submit(BundleRequest request, BundleExecutionContext context);

  /**
   * Submits only selector parameters: the registered {@link BundleDocumentSelector} compiles the
   * document list when the job executes, so generation is a snapshot at execution and documents
   * arriving between submission and execution are naturally included.
   *
   * @param externalId the consumer-minted idempotency key
   * @param selectorParameters the parameters the selector compiles into a request, for example a
   *     case reference or hearing id
   * @param context the non-secret consumer context persisted with the job
   * @return the queued job, or the existing job for a repeated external id
   */
  BundleJob submit(UUID externalId, Map<String, String> selectorParameters,
      BundleExecutionContext context);

  /**
   * Looks up a job by its external id.
   *
   * @param externalId the consumer-minted idempotency key
   * @return the job, or empty if none was ever submitted with the id
   */
  Optional<BundleJob> find(UUID externalId);
}
