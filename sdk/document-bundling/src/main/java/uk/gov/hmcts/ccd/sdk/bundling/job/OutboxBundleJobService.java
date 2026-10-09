package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/**
 * Durable, asynchronous bundle execution over a transactional outbox in the consuming service's
 * own database. Submission is one INSERT through the consumer's JDBC template, so the row joins
 * whatever transaction is active in the caller and exists exactly when that change commits. The
 * consumer-minted external id is the idempotency key: a repeated submission inserts nothing and
 * returns the existing job. Nothing secret is persisted: no tokens, source bytes or signed URLs.
 * The supported isolation level for submitting transactions is READ COMMITTED: under REPEATABLE
 * READ or SERIALIZABLE a coalesced submission racing another can fail with a serialization error
 * (SQLSTATE 40001) that the caller must retry.
 */
@Slf4j
public class OutboxBundleJobService {

  private static final int MAX_COALESCE_KEY_LENGTH = 255;

  private final BundleJobRepository repository;
  private final BundleJobJson json = new BundleJobJson();

  public OutboxBundleJobService(BundleJobRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
  }

  /** Submits a fully built request; generation is a snapshot at submission. */
  public BundleJob submit(BundleRequest request, BundleExecutionContext context) {
    Objects.requireNonNull(request, "request must not be null");
    return enqueue(request.externalId(), json.write(request), Map.of(), context);
  }

  /**
   * Submits only selector parameters: the registered document selector compiles the request when
   * the job executes, so documents arriving between submission and execution are included.
   */
  public BundleJob submit(UUID externalId, Map<String, String> selectorParameters,
      BundleExecutionContext context) {
    return enqueue(externalId, null, selectorParameters, context);
  }

  /**
   * Submits selector parameters under a coalesce key, for a bundle that is regenerated whenever
   * its inputs change (key it by case and bundle kind). If a job with the same key is still
   * waiting for its first claim, nothing is inserted and that job is returned: its selector has
   * not run yet, and it cannot be claimed until this transaction ends, so it will pick up
   * whatever triggered this submission. Once a job is claimed it stops absorbing submissions, so
   * a change made while a render is in flight queues one follow-up.
   *
   * <p>The waiting job keeps the parameters and context it was first submitted with, so the
   * parameters must be a function of the key; a submission whose parameters differ is logged.
   * Joining locks the waiting row until commit: a transaction submitting several keys should
   * submit them in a consistent order, or two such transactions can deadlock.
   */
  public BundleJob submitCoalesced(String coalesceKey, Map<String, String> selectorParameters,
      BundleExecutionContext context) {
    if (coalesceKey == null || coalesceKey.isBlank()) {
      throw new IllegalArgumentException("coalesceKey must not be blank");
    }
    // varchar(255) counts characters, not UTF-16 code units.
    int keyLength = coalesceKey.codePointCount(0, coalesceKey.length());
    if (keyLength > MAX_COALESCE_KEY_LENGTH) {
      throw new IllegalArgumentException("coalesceKey must be at most " + MAX_COALESCE_KEY_LENGTH
          + " characters, was " + keyLength);
    }
    Objects.requireNonNull(selectorParameters, "selectorParameters must not be null");
    Objects.requireNonNull(context, "context must not be null");
    String parameters = json.write(selectorParameters);
    UUID minted = UUID.randomUUID();
    BundleJobRepository.CoalescedInsert landed =
        repository.insertOrJoin(minted, coalesceKey, parameters, json.write(context));
    if (!landed.externalId().equals(minted)) {
      log.info("Bundle job submission for {} coalesced onto waiting job {}", coalesceKey,
          landed.externalId());
      if (!json.readParameters(landed.storedSelectorParametersJson())
          .equals(selectorParameters)) {
        log.warn("Bundle job submission for {} carried selector parameters that differ from "
            + "those of waiting job {}; the waiting job's parameters are kept", coalesceKey,
            landed.externalId());
      }
    }
    return repository.find(landed.externalId()).orElseThrow();
  }

  public Optional<BundleJob> find(UUID externalId) {
    return repository.find(Objects.requireNonNull(externalId, "externalId must not be null"));
  }

  /**
   * The key's job reflecting the newest state, for a "regenerating..." status: the job still
   * waiting if there is one, otherwise the most recently claimed.
   */
  public Optional<BundleJob> findLatest(String coalesceKey) {
    return repository.findLatest(Objects.requireNonNull(coalesceKey, "coalesceKey"));
  }

  private BundleJob enqueue(UUID externalId, String requestJson, Map<String, String> parameters,
      BundleExecutionContext context) {
    Objects.requireNonNull(externalId, "externalId must not be null");
    Objects.requireNonNull(parameters, "selectorParameters must not be null");
    Objects.requireNonNull(context, "context must not be null");
    if (!repository.insertIfAbsent(externalId, requestJson, json.write(parameters),
        json.write(context))) {
      log.warn("Bundle job {} was already submitted; returning the existing job", externalId);
    }
    return repository.find(externalId).orElseThrow(() -> new IllegalStateException(
        "Bundle job " + externalId + " was not visible immediately after submission"));
  }
}
