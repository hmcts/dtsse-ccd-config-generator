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
 * The supported isolation level for submitting transactions is READ COMMITTED.
 */
@Slf4j
public class OutboxBundleJobService {

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

  public Optional<BundleJob> find(UUID externalId) {
    return repository.find(Objects.requireNonNull(externalId, "externalId must not be null"));
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
