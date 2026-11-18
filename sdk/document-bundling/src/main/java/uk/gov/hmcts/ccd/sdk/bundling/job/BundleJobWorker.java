package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.RenderAttempt;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;

/**
 * The scheduled worker: each poll fails bundles whose renders exhausted their attempts, claims a
 * batch of queue rows under a lease, and for each one selects the documents, renders, stores the
 * PDF and records the outcome. A rendered bundle is recorded
 * in bundling.bundle, so the consuming service stores only the PDF and reads everything else about
 * the bundle back through BundleJobRepository. Writes are lease-guarded, so a render that outlives
 * its lease can never overwrite the reclaimer's state. Only transient resolution and conversion
 * failures are retried, and never rendered around: while a dependency is down no bundle is
 * published and the current one stays. Untyped exception detail is logged, never persisted. The consuming service
 * enables scheduling for the poll to fire.
 */
@Slf4j
public class BundleJobWorker implements AutoCloseable {

  private static final String LOG_ONLY =
      ". The exception detail is in the service logs, not this record.";

  private final BundleJobRepository repository;
  private final BundleRenderer renderer;
  private final ManagedCaseBundles bundles;
  private final BundleJobRetryPolicy retryPolicy;
  private final Executor executor;
  private final ExecutorService ownedExecutor;
  private final int batchSize;
  private final int maxConcurrentRenders;
  private final Duration leaseDuration;
  private final String workerId = "bundle-job-worker-" + UUID.randomUUID();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final BundleJobJson json = new BundleJobJson();

  /** Creates a worker rendering on its own fixed pool, shut down by close(). */
  public BundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      ManagedCaseBundles bundles, BundleJobRetryPolicy retryPolicy, int batchSize,
      int maxConcurrentRenders, Duration leaseDuration) {
    this(repository, renderer, bundles, retryPolicy,
        Executors.newFixedThreadPool(maxConcurrentRenders), true, batchSize,
        maxConcurrentRenders, leaseDuration);
  }

  /** Creates a worker rendering on the caller's executor, whose lifecycle stays with the caller. */
  BundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      ManagedCaseBundles bundles, BundleJobRetryPolicy retryPolicy, Executor executor,
      int batchSize, int maxConcurrentRenders, Duration leaseDuration) {
    this(repository, renderer, bundles, retryPolicy, executor, false, batchSize,
        maxConcurrentRenders, leaseDuration);
  }

  private BundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      ManagedCaseBundles bundles, BundleJobRetryPolicy retryPolicy, Executor executor,
      boolean ownsExecutor, int batchSize, int maxConcurrentRenders, Duration leaseDuration) {
    if (leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    this.repository = repository;
    this.renderer = renderer;
    this.bundles = bundles;
    this.retryPolicy = retryPolicy;
    this.executor = executor;
    this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
    this.batchSize = batchSize;
    this.maxConcurrentRenders = maxConcurrentRenders;
    this.leaseDuration = leaseDuration;
  }

  /** Reaps exhausted stale jobs, then claims up to the free render capacity and dispatches. */
  @Scheduled(fixedDelayString = "${ccd.bundling.job.worker.poll-delay:1000}")
  public void poll() {
    int toClaim = Math.min(batchSize, maxConcurrentRenders - inFlight.get());
    if (toClaim <= 0) {
      return;
    }
    for (String exhausted : repository.failExhaustedStaleJobs(retryPolicy.maxAttempts(),
        bundles.definitions())) {
      log.error("Bundle job {} exhausted its {} attempt(s) without recording a result; FAILED",
          exhausted, retryPolicy.maxAttempts());
    }
    // A lease owner per poll: a job this worker reclaims after its lease expired gets a new one.
    String leaseOwner = workerId + "/" + UUID.randomUUID();
    for (ClaimedBundleJob claimed : repository.claim(toClaim, leaseOwner, leaseDuration,
        retryPolicy.maxAttempts(), bundles.definitions())) {
      inFlight.incrementAndGet();
      try {
        executor.execute(() -> {
          try {
            execute(claimed);
          } catch (RuntimeException e) {
            log.error("Bundle job {} failed unexpectedly outside the render pipeline",
                claimed.job(), e);
          } finally {
            inFlight.decrementAndGet();
          }
        });
      } catch (RejectedExecutionException e) {
        inFlight.decrementAndGet();
        log.warn("Bundle job {} dispatch was rejected; it is reclaimable when its lease expires",
            claimed.job(), e);
      }
    }
  }

  @Override
  public void close() {
    if (ownedExecutor != null) {
      ownedExecutor.shutdown();
    }
  }

  private void execute(ClaimedBundleJob claimed) {
    BundleJob job = claimed.job();
    SelectedCaseBundle selected;
    try {
      selected = bundles.select(job);
    } catch (BundleGenerationException e) {
      handleGenerationFailure(claimed, e);
      return;
    } catch (DataAccessException | TransactionException e) {
      // The database, not the request: the lease expires and the job is reclaimed.
      log.warn("Bundle job {} could not read its selection", job, e);
      return;
    } catch (RuntimeException e) {
      log.error("Bundle job {} request could not be compiled for execution", job, e);
      failTerminally(claimed, BundleErrorCode.JOB_REQUEST_UNREADABLE, "The bundle request could not be "
          + "compiled for execution; the definition threw " + e.getClass().getName() + LOG_ONLY,
          List.of());
      return;
    }

    BundleRequest request = selected.request();
    BundleExecutionContext context = BundleExecutionContext.builder()
        .caseReference(String.valueOf(job.caseReference())).build();
    try (BundleResult result = renderer.render(request, context, RenderAttempt.RETRYABLE)) {
      StoredBundleDocument stored;
      try {
        stored = bundles.store(selected, result);
      } catch (Exception e) {
        completionFailed(claimed, e);
        return;
      }
      log.info("Bundle job {} completed as {} after {} attempt(s)", job, result.outcome(),
          job.attempts());
      boolean completed = Boolean.TRUE.equals(repository.holdingCase(job.caseReference(),
          () -> repository.markCompleted(claimed, bundleRecord(selected, result, stored))));
      if (!completed) {
        leaseLost(job, "completion");
        discard(job, stored);
      }
    } catch (BundleGenerationException e) {
      handleGenerationFailure(claimed, e);
    } catch (DataAccessException | TransactionException e) {
      // The completion may or may not have committed; the lease expires and the job is reclaimed.
      log.error("Bundle job {} rendered but its completion could not be recorded", job, e);
    } catch (RuntimeException e) {
      log.error("Bundle job {} failed with an untyped renderer error", job, e);
      failTerminally(claimed, BundleErrorCode.ASSEMBLY_FAILED,
          "Unexpected renderer failure of type " + e.getClass().getName() + LOG_ONLY,
          List.of());
    }
  }

  /** The rendered bundle as recorded in bundling.bundle, with the job's completed state. */
  private BundleJobRepository.BundleRecord bundleRecord(SelectedCaseBundle selected,
      BundleResult result, StoredBundleDocument stored) {
    return new BundleJobRepository.BundleRecord(result.outcome().name(),
        result.artifact().fileName(), result.artifact().mediaType(), result.artifact().size(),
        result.artifact().sha256(), result.pageCount(), json.write(selected.request()),
        json.write(result.documents()), json.write(result.missingDocuments()),
        json.write(result.warnings()), stored);
  }

  private void completionFailed(ClaimedBundleJob claimed, Exception failure) {
    BundleJob job = claimed.job();
    Optional<Instant> next = retryPolicy.nextAttemptAt(claimed.job().attempts(), Instant.now());
    if (bundles.retryable(failure) && next.isPresent()) {
      log.warn("Bundle job {} rendered but its PDF could not be stored; retrying at {}", job,
          next.get(), failure);
      if (!repository.requeueForRetry(claimed, next.get())) {
        leaseLost(job, "completion retry");
      }
      return;
    }
    log.error("Bundle job {} rendered but its PDF could not be stored", job, failure);
    failTerminally(claimed, BundleErrorCode.COMPLETION_FAILED, "The bundle rendered but storing it "
        + "threw " + failure.getClass().getName() + LOG_ONLY, List.of());
  }

  private void discard(BundleJob job, StoredBundleDocument stored) {
    try {
      bundles.discard(stored);
    } catch (RuntimeException e) {
      log.warn("Bundle job {} could not delete the PDF it stored", job, e);
    }
  }

  private void handleGenerationFailure(ClaimedBundleJob claimed, BundleGenerationException failure) {
    BundleJob job = claimed.job();
    int attempts = job.attempts();
    if (!retryPolicy.isTransient(failure)) {
      log.error("Bundle job {} failed with non-retryable {}: {}", job, failure.code(),
          failure.getMessage());
      failTerminally(claimed, failure.code(), failure.getMessage(), failure.documentFailures());
      return;
    }
    Optional<Instant> nextAttemptAt = retryPolicy.nextAttemptAt(attempts, Instant.now());
    if (nextAttemptAt.isPresent()) {
      log.warn("Bundle job {} failed transiently with {} on attempt {} of {}; retrying at {}: {}",
          job, failure.code(), attempts, retryPolicy.maxAttempts(), nextAttemptAt.get(),
          failure.getMessage());
      if (!repository.requeueForRetry(claimed, nextAttemptAt.get())) {
        leaseLost(job, "transient retry");
      }
      return;
    }
    log.error("Bundle job {} failed with {} and exhausted its {} attempt(s)", job, failure.code(),
        attempts);
    failTerminally(claimed, failure.code(),
        failure.getMessage() + " Retries exhausted after " + attempts + " attempt(s).",
        failure.documentFailures());
  }

  private void failTerminally(ClaimedBundleJob claimed, BundleErrorCode code, String message,
      List<DocumentFailure> documentFailures) {
    if (!repository.markFailed(claimed, code, message, json.write(documentFailures))) {
      leaseLost(claimed.job(), "failure");
    }
  }

  private void leaseLost(BundleJob job, String write) {
    log.warn("Bundle job {} {} was not recorded: this worker ({}) no longer holds the lease; "
        + "the lease holder's state stands", job, write, workerId);
  }
}
