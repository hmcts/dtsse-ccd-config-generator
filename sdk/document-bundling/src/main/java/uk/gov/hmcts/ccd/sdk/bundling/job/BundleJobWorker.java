package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocumentReason;
import uk.gov.hmcts.ccd.sdk.bundling.api.RenderAttempt;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;

/**
 * The scheduled worker: each poll fails stale jobs that exhausted their attempts, claims a batch
 * of executable rows under a lease, and for each one runs the document selector, renders, hands
 * the open result to the completion handler and records the terminal state. A completed job's
 * bundle is recorded in bundling.bundle with that state, so the consuming service stores only the
 * PDF and reads everything else about the bundle back through BundleJobRepository. Terminal writes are
 * lease-guarded, so a render that outlives its lease can never overwrite the reclaimer's state.
 * Only transient resolution and conversion failures are retried; untyped exception detail is
 * logged, never persisted. The consuming service enables scheduling for the poll to fire.
 */
@Slf4j
public class BundleJobWorker implements AutoCloseable {

  private static final String LOG_ONLY =
      ". The exception detail is in the service logs, not this record.";

  private final BundleJobRepository repository;
  private final BundleRenderer renderer;
  private final BundleDocumentSelector selector;
  private final BundleJobCompletionHandler completionHandler;
  private final BundleJobRetryPolicy retryPolicy;
  private final List<BundleProgressListener> listeners;
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
      BundleDocumentSelector selector, BundleJobCompletionHandler completionHandler,
      BundleJobRetryPolicy retryPolicy, List<BundleProgressListener> listeners, int batchSize,
      int maxConcurrentRenders, Duration leaseDuration) {
    this(repository, renderer, selector, completionHandler, retryPolicy, listeners,
        Executors.newFixedThreadPool(maxConcurrentRenders), true, batchSize,
        maxConcurrentRenders, leaseDuration);
  }

  /** Creates a worker rendering on the caller's executor, whose lifecycle stays with the caller. */
  public BundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      BundleDocumentSelector selector, BundleJobCompletionHandler completionHandler,
      BundleJobRetryPolicy retryPolicy, List<BundleProgressListener> listeners,
      Executor executor, int batchSize, int maxConcurrentRenders, Duration leaseDuration) {
    this(repository, renderer, selector, completionHandler, retryPolicy, listeners, executor,
        false, batchSize, maxConcurrentRenders, leaseDuration);
  }

  private BundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      BundleDocumentSelector selector, BundleJobCompletionHandler completionHandler,
      BundleJobRetryPolicy retryPolicy, List<BundleProgressListener> listeners,
      Executor executor, boolean ownsExecutor, int batchSize, int maxConcurrentRenders,
      Duration leaseDuration) {
    if (leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    this.repository = repository;
    this.renderer = renderer;
    this.selector = selector;
    this.completionHandler = completionHandler;
    this.retryPolicy = retryPolicy;
    this.listeners = List.copyOf(listeners);
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
    for (UUID exhausted : repository.failExhaustedStaleJobs(retryPolicy.maxAttempts(),
        selector.definitions())) {
      log.error("Bundle job {} exhausted its {} attempt(s) without recording a result; FAILED",
          exhausted, retryPolicy.maxAttempts());
      emit(exhausted, BundleJobState.FAILED, 0, 0);
    }
    // A lease owner per poll: a job this worker reclaims after its lease expired gets a new one.
    String leaseOwner = workerId + "/" + UUID.randomUUID();
    for (ClaimedBundleJob claimed : repository.claim(toClaim, leaseOwner, leaseDuration,
        retryPolicy.maxAttempts(), selector.definitions())) {
      inFlight.incrementAndGet();
      try {
        executor.execute(() -> {
          try {
            execute(claimed);
          } catch (RuntimeException e) {
            log.error("Bundle job {} failed unexpectedly outside the render pipeline",
                claimed.job().externalId(), e);
          } finally {
            inFlight.decrementAndGet();
          }
        });
      } catch (RejectedExecutionException e) {
        inFlight.decrementAndGet();
        log.warn("Bundle job {} dispatch was rejected; it is reclaimable when its lease expires",
            claimed.job().externalId(), e);
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
    UUID id = job.externalId();
    SelectedCaseBundle selected;
    try {
      selected = selector.select(job);
    } catch (BundleGenerationException e) {
      handleGenerationFailure(claimed, e, 0);
      return;
    } catch (DataAccessException | TransactionException e) {
      // The database, not the request: the lease expires and the job is reclaimed.
      log.warn("Bundle job {} could not read its selection", id, e);
      return;
    } catch (RuntimeException e) {
      log.error("Bundle job {} request could not be compiled for execution", id, e);
      failTerminally(claimed, BundleErrorCode.JOB_REQUEST_UNREADABLE, "The bundle request could not be "
          + "compiled for execution; the selector threw " + e.getClass().getName() + LOG_ONLY,
          List.of(), 0);
      return;
    }

    if (selected == null) {
      log.info("Bundle job {} leaves its bundle to a job claimed after it", id);
      record(repository.markSuperseded(id, claimed.leaseOwner()), id, BundleJobState.COMPLETED, 0, 0,
          "completion");
      return;
    }

    BundleRequest request = selected.request();
    int total = request.allDocuments().size();
    emit(id, BundleJobState.IN_PROGRESS, 0, total);
    // A source that is only temporarily unavailable fails the render while retries remain, and
    // becomes a placeholder on the last attempt.
    RenderAttempt attempt = retryPolicy.nextAttemptAt(job.attempts(), Instant.now()).isPresent()
        ? RenderAttempt.RETRYABLE : RenderAttempt.FINAL;
    BundleExecutionContext context = BundleExecutionContext.builder()
        .caseReference(String.valueOf(job.caseReference())).build();
    try (BundleResult result = renderer.render(request, context, attempt)) {
      StoredBundleDocument stored;
      try {
        stored = completionHandler.onCompleted(job, selected, result);
      } catch (Exception e) {
        completionFailed(claimed, e, total);
        return;
      }
      BundleJobState terminal = result.outcome() == BundleOutcome.COMPLETED_WITH_WARNINGS
          ? BundleJobState.COMPLETED_WITH_WARNINGS : BundleJobState.COMPLETED;
      log.info("Bundle job {} completed as {} after {} attempt(s)", id, terminal, job.attempts());
      boolean completed = Boolean.TRUE.equals(repository.holdingCase(job.caseReference(), () -> {
        boolean marked = repository.markCompleted(id, terminal,
            bundleRecord(selected, result, stored), claimed.leaseOwner());
        if (marked) {
          requeueIfUnavailable(claimed, result);
        }
        return marked;
      }));
      record(completed, id, terminal, total, total, "completion");
      if (!completed) {
        discard(job, stored);
      }
    } catch (BundleGenerationException e) {
      handleGenerationFailure(claimed, e, total);
    } catch (DataAccessException | TransactionException e) {
      // The completion may or may not have committed; the lease expires and the job is reclaimed.
      log.error("Bundle job {} rendered but its completion could not be recorded", id, e);
    } catch (RuntimeException e) {
      log.error("Bundle job {} failed with an untyped renderer error", id, e);
      failTerminally(claimed, BundleErrorCode.ASSEMBLY_FAILED,
          "Unexpected renderer failure of type " + e.getClass().getName() + LOG_ONLY,
          List.of(), total);
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

  private void completionFailed(ClaimedBundleJob claimed, Exception failure, int total) {
    UUID id = claimed.job().externalId();
    Optional<Instant> next = retryPolicy.nextAttemptAt(claimed.job().attempts(), Instant.now());
    if (completionHandler.retryable(failure) && next.isPresent()) {
      log.warn("Bundle job {} rendered but its completion handler failed; retrying at {}", id,
          next.get(), failure);
      record(repository.requeueForRetry(id, next.get(), claimed.transientHistoryJson(),
          claimed.leaseOwner()),
          id, BundleJobState.QUEUED, 0, total, "completion retry");
      return;
    }
    log.error("Bundle job {} rendered but its completion handler failed", id, failure);
    failTerminally(claimed, BundleErrorCode.COMPLETION_FAILED, "The bundle rendered but the "
        + "completion handler threw " + failure.getClass().getName() + LOG_ONLY, List.of(), total);
  }

  private void discard(BundleJob job, StoredBundleDocument stored) {
    try {
      completionHandler.discarded(job, stored);
    } catch (Exception e) {
      log.warn("Bundle job {} could not discard what its completion handler stored", job.externalId(),
          e);
    }
  }

  /**
   * Queues a delayed re-run of a bundle that has placeholders for documents that were only
   * temporarily unavailable, so it fills in once they can be fetched. A change to the bundle's
   * inputs before then joins the re-run and makes it immediate. It commits with the completion.
   */
  private void requeueIfUnavailable(ClaimedBundleJob claimed, BundleResult result) {
    if (result.missingDocuments().stream()
        .noneMatch(missing -> missing.reason() == MissingDocumentReason.UNAVAILABLE)) {
      return;
    }
    BundleJob job = claimed.job();
    UUID id = job.externalId();
    int requeues = claimed.unavailableRequeues();
    Optional<Instant> runAt = retryPolicy.requeueAt(requeues, Instant.now());
    if (runAt.isEmpty()) {
      log.warn("Bundle job {} has placeholders for temporarily unavailable documents and has "
          + "been re-run {} time(s); not re-running it again", id, requeues);
      return;
    }
    // A re-run of a refresh job stays behind live changes.
    UUID landed = repository.insertOrJoin(UUID.randomUUID(), job.definition(), job.caseReference(),
        runAt.get(), requeues + 1, claimed.priority());
    log.info("Bundle job {} has placeholders for temporarily unavailable documents; job {} "
        + "re-runs it from {}", id, landed, runAt.get());
  }

  private void handleGenerationFailure(ClaimedBundleJob claimed, BundleGenerationException failure,
      int total) {
    UUID id = claimed.job().externalId();
    int attempts = claimed.job().attempts();
    if (!retryPolicy.isTransient(failure)) {
      log.error("Bundle job {} failed with non-retryable {}: {}", id, failure.code(),
          failure.getMessage());
      failTerminally(claimed, failure.code(), failure.getMessage(), failure.documentFailures(),
          total);
      return;
    }
    List<BundleJobTransientFailure> history =
        new ArrayList<>(json.readHistory(claimed.transientHistoryJson()));
    history.add(new BundleJobTransientFailure(attempts, failure.code(), failure.getMessage(),
        Instant.now()));
    Optional<Instant> nextAttemptAt = retryPolicy.nextAttemptAt(attempts, Instant.now());
    if (nextAttemptAt.isPresent()) {
      log.warn("Bundle job {} failed transiently with {} on attempt {} of {}; retrying at {}",
          id, failure.code(), attempts, retryPolicy.maxAttempts(), nextAttemptAt.get());
      record(repository.requeueForRetry(id, nextAttemptAt.get(), json.write(history),
          claimed.leaseOwner()),
          id, BundleJobState.QUEUED, 0, total, "transient retry");
      return;
    }
    String message = failure.getMessage() + " Retries exhausted after " + attempts
        + " attempt(s). Transient history: "
        + history.stream().map(BundleJobTransientFailure::describe).collect(Collectors.joining("; "))
        + ".";
    log.error("Bundle job {} failed with {} and exhausted its {} attempt(s)", id, failure.code(),
        attempts);
    record(repository.markFailed(id, failure.code(), message,
        json.write(failure.documentFailures()), json.write(history), claimed.leaseOwner()),
        id, BundleJobState.FAILED, 0, total, "exhausted failure");
  }

  private void failTerminally(ClaimedBundleJob claimed, BundleErrorCode code, String message,
      List<DocumentFailure> documentFailures, int total) {
    UUID id = claimed.job().externalId();
    record(repository.markFailed(id, code, message, json.write(documentFailures), null,
        claimed.leaseOwner()), id, BundleJobState.FAILED, 0, total, "failure");
  }

  private void record(boolean written, UUID id, BundleJobState state, int completed, int total,
      String write) {
    if (written) {
      emit(id, state, completed, total);
    } else {
      log.warn("Bundle job {} {} was not recorded: this worker ({}) no longer holds the lease; "
          + "the lease holder's state stands", id, write, workerId);
    }
  }

  private void emit(UUID id, BundleJobState state, int completed, int total) {
    BundleProgressEvent event = new BundleProgressEvent(id, state, completed, total);
    for (BundleProgressListener listener : listeners) {
      try {
        listener.onProgress(event);
      } catch (RuntimeException e) {
        log.warn("A progress listener failed for bundle job {}; the job is unaffected", id, e);
      }
    }
  }
}
