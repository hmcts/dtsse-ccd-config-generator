package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;

/**
 * The scheduled worker of the durable job runner: one poll first fails any stale job that has
 * exhausted its attempt bound without ever recording a result, then claims a small batch of
 * executable outbox rows with {@code FOR UPDATE SKIP LOCKED} and a lease, and runs each claimed
 * job — call the registered {@link BundleDocumentSelector}, render through the
 * {@link BundleRenderer}, record the terminal state — respecting a configurable maximum of
 * concurrent renders. No Spring Batch, callback URL, separate service, or distributed scheduler
 * lock is involved; contending workers skip each other's rows, and a stale lease is reclaimable
 * by any worker.
 *
 * <p><strong>Lease invariant.</strong> The configured lease duration MUST exceed the renderer's
 * enforced end-to-end timeout ({@code BundleLimits.maxElapsed}, one minute by default) with
 * comfortable headroom. A render that outlives its lease is reclaimed and rendered again — that
 * double render is inherent to lease-based recovery — but every terminal write is lease-guarded,
 * so only the current lease holder's completion or failure is ever recorded; a worker that lost
 * its lease detects the rejected write and logs it instead of overwriting the reclaimer's state.
 * The renderer interface does not expose its timeout, so this invariant is documented rather
 * than validated here; keep {@code ccd.bundling.job.worker.lease-duration} well above the
 * render timeout.
 *
 * <p>Failures are recorded sanitised: typed {@link BundleGenerationException} messages are
 * persisted as designed, while untyped exception detail is only ever logged — the job row gets
 * the exception class name, never the raw message, which can carry signed URLs or other
 * sensitive material. Only typed transient failures are retried, under the bounded
 * {@link BundleJobRetryPolicy}, whether they arise in the selector or the renderer; when retries
 * exhaust the job fails carrying its transient history. A persisted request the worker cannot
 * read fails clearly with {@link BundleErrorCode#JOB_REQUEST_UNREADABLE}. Progress is emitted to
 * every registered {@link BundleProgressListener}; a throwing listener never breaks the job.
 *
 * <p>{@link #poll()} is annotated {@code @Scheduled}; the consuming service enables scheduling
 * ({@code @EnableScheduling}) and configures the delay through
 * {@code ccd.bundling.job.worker.poll-delay}.
 */
@Slf4j
public class BundleJobWorker implements AutoCloseable {

  private final BundleJobRepository repository;
  private final BundleRenderer renderer;
  private final BundleDocumentSelector selector;
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

  /**
   * Creates a worker that renders on its own fixed thread pool, sized to the concurrent-render
   * limit and shut down by {@link #close()}.
   *
   * @param repository the outbox repository
   * @param renderer the rendering engine
   * @param selector the execution-time document selector
   * @param retryPolicy the bounded transient-failure retry policy
   * @param listeners the progress listeners; may be empty
   * @param batchSize the maximum number of jobs one poll claims
   * @param maxConcurrentRenders the maximum number of renders in flight at once
   * @param leaseDuration how long a claim's lease lasts before the job is reclaimable; must
   *     comfortably exceed the renderer's enforced end-to-end timeout
   */
  public BundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      BundleDocumentSelector selector, BundleJobRetryPolicy retryPolicy,
      List<BundleProgressListener> listeners, int batchSize, int maxConcurrentRenders,
      Duration leaseDuration) {
    this(repository, renderer, selector, retryPolicy, listeners,
        Executors.newFixedThreadPool(maxConcurrentRenders), true, batchSize,
        maxConcurrentRenders, leaseDuration);
  }

  /**
   * Creates a worker that renders on the given executor. The executor's lifecycle stays with
   * the caller; {@link #close()} does not shut it down.
   *
   * @param repository the outbox repository
   * @param renderer the rendering engine
   * @param selector the execution-time document selector
   * @param retryPolicy the bounded transient-failure retry policy
   * @param listeners the progress listeners; may be empty
   * @param executor the executor renders run on
   * @param batchSize the maximum number of jobs one poll claims
   * @param maxConcurrentRenders the maximum number of renders in flight at once
   * @param leaseDuration how long a claim's lease lasts before the job is reclaimable; must
   *     comfortably exceed the renderer's enforced end-to-end timeout
   */
  public BundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      BundleDocumentSelector selector, BundleJobRetryPolicy retryPolicy,
      List<BundleProgressListener> listeners, Executor executor, int batchSize,
      int maxConcurrentRenders, Duration leaseDuration) {
    this(repository, renderer, selector, retryPolicy, listeners, executor, false, batchSize,
        maxConcurrentRenders, leaseDuration);
  }

  private BundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      BundleDocumentSelector selector, BundleJobRetryPolicy retryPolicy,
      List<BundleProgressListener> listeners, Executor executor, boolean ownsExecutor,
      int batchSize, int maxConcurrentRenders, Duration leaseDuration) {
    if (leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("leaseDuration must be positive, and must comfortably "
          + "exceed the renderer's enforced end-to-end timeout");
    }
    this.repository = repository;
    this.renderer = renderer;
    this.selector = selector;
    this.retryPolicy = retryPolicy;
    this.listeners = List.copyOf(listeners);
    this.executor = executor;
    this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
    this.batchSize = batchSize;
    this.maxConcurrentRenders = maxConcurrentRenders;
    this.leaseDuration = leaseDuration;
  }

  /**
   * Sweeps crash-looped stale jobs to FAILED, then claims up to the batch size of executable
   * jobs — bounded further by the remaining concurrent-render capacity — and dispatches each to
   * the render executor. A dispatch rejected by a saturated executor releases its claim
   * immediately (the attempt is handed back) and never leaks render capacity.
   */
  @Scheduled(fixedDelayString = "${ccd.bundling.job.worker.poll-delay:1000}")
  public void poll() {
    int capacity = maxConcurrentRenders - inFlight.get();
    int toClaim = Math.min(batchSize, capacity);
    if (toClaim <= 0) {
      return;
    }
    for (UUID exhausted : repository.failExhaustedStaleJobs(retryPolicy.maxAttempts())) {
      log.error("Bundle job {} exhausted its {} attempt(s) without recording a result "
          + "(crashed or terminated renders); marked FAILED", exhausted,
          retryPolicy.maxAttempts());
      emit(exhausted, BundleJobState.FAILED, 0, 0);
    }
    List<ClaimedBundleJob> claimed =
        repository.claim(toClaim, workerId, leaseDuration, retryPolicy.maxAttempts());
    for (ClaimedBundleJob job : claimed) {
      inFlight.incrementAndGet();
      try {
        executor.execute(() -> {
          try {
            execute(job);
          } catch (RuntimeException e) {
            log.error("Bundle job {} failed unexpectedly outside the render pipeline",
                job.externalId(), e);
          } catch (Error e) {
            log.error("Bundle job {} died with an Error; the job stays leased for stale-lease "
                + "recovery up to the attempt bound", job.externalId(), e);
            throw e;
          } finally {
            inFlight.decrementAndGet();
          }
        });
      } catch (RejectedExecutionException e) {
        inFlight.decrementAndGet();
        boolean released = repository.releaseClaim(job.externalId(), workerId);
        log.warn("Bundle job {} dispatch was rejected by the render executor; claim {} "
            + "(attempt handed back)", job.externalId(),
            released ? "released back to the queue" : "was no longer ours to release", e);
      }
    }
  }

  /**
   * Shuts down the internally created render pool, if this worker owns one.
   */
  @Override
  public void close() {
    if (ownedExecutor != null) {
      ownedExecutor.shutdown();
    }
  }

  private void execute(ClaimedBundleJob job) {
    UUID id = job.externalId();
    BundleRequest request;
    BundleExecutionContext context;
    try {
      if (job.requestVersion() > BundleJobJson.REQUEST_VERSION) {
        throw new BundleJobPayloadException(
            "the job was stored with request version " + job.requestVersion()
                + " but this worker reads up to version " + BundleJobJson.REQUEST_VERSION);
      }
      Optional<BundleRequest> submitted = job.requestJson() == null
          ? Optional.empty()
          : Optional.of(json.readRequest(job.requestJson()));
      context = json.readContext(job.executionContextJson());
      Map<String, String> parameters = json.readParameters(job.selectorParametersJson());
      request = selector.select(new BundleJobContext(id, submitted, parameters, context));
    } catch (BundleJobPayloadException e) {
      log.error("Bundle job {} is unreadable by this worker: {}", id, e.getMessage(), e);
      failTerminally(id, BundleErrorCode.JOB_REQUEST_UNREADABLE,
          "Bundle job " + id + " could not be read by this worker: " + e.getMessage()
              + " Re-submit the bundle under a new external id.",
          List.of(), 0);
      return;
    } catch (BundleGenerationException e) {
      // An execution-time selector reads case data over the network; its typed failures get
      // the same transient-versus-terminal treatment as render failures.
      handleGenerationFailure(job, e, 0);
      return;
    } catch (RuntimeException e) {
      log.error("Bundle job {} request could not be compiled for execution", id, e);
      failTerminally(id, BundleErrorCode.REQUEST_INVALID,
          "The bundle request could not be compiled for execution; the selector threw "
              + e.getClass().getName() + ". The exception detail is in the service logs, not "
              + "this record.",
          List.of(), 0);
      return;
    }

    int total = request.allDocuments().size();
    emit(id, BundleJobState.RESOLVING, 0, total);
    try {
      BundleResult result = renderer.render(request, context);
      BundleJobState terminal = completionState(result.outcome());
      if (repository.markCompleted(id, terminal, json.writeBundle(result.output()), workerId)) {
        log.info("Bundle job {} completed as {} after {} attempt(s)", id, terminal,
            job.attempts());
        emit(id, terminal, total, total);
      } else {
        logLostLease(id, "completion");
      }
    } catch (BundleGenerationException e) {
      handleGenerationFailure(job, e, total);
    } catch (RuntimeException e) {
      log.error("Bundle job {} failed with an untyped renderer error", id, e);
      failTerminally(id, BundleErrorCode.ASSEMBLY_FAILED,
          "Unexpected renderer failure of type " + e.getClass().getName()
              + ". The exception detail is in the service logs, not this record.",
          List.of(), total);
    }
  }

  /**
   * The terminal state for a successful render outcome.
   *
   * @param outcome the renderer's outcome
   * @return the matching terminal job state
   */
  static BundleJobState completionState(BundleOutcome outcome) {
    return outcome == BundleOutcome.COMPLETED_WITH_WARNINGS
        ? BundleJobState.COMPLETED_WITH_WARNINGS
        : BundleJobState.COMPLETED;
  }

  private void handleGenerationFailure(ClaimedBundleJob job, BundleGenerationException failure,
      int total) {
    UUID id = job.externalId();
    if (!retryPolicy.isTransient(failure.code())) {
      log.error("Bundle job {} failed with non-retryable {}: {}", id, failure.code(),
          failure.getMessage());
      failTerminally(id, failure.code(), failure.getMessage(), failure.documentFailures(), total);
      return;
    }

    List<BundleJobTransientFailure> history =
        new ArrayList<>(json.readHistory(job.transientHistoryJson()));
    history.add(new BundleJobTransientFailure(
        job.attempts(), failure.code(), failure.getMessage(), Instant.now()));
    Optional<Instant> nextAttemptAt = retryPolicy.nextAttemptAt(job.attempts(), Instant.now());
    if (nextAttemptAt.isPresent()) {
      if (repository.requeueForRetry(id, nextAttemptAt.get(), json.writeHistory(history),
          workerId)) {
        log.warn("Bundle job {} failed transiently with {} on attempt {} of {}; retrying at {}",
            id, failure.code(), job.attempts(), retryPolicy.maxAttempts(), nextAttemptAt.get());
        emit(id, BundleJobState.QUEUED, 0, total);
      } else {
        logLostLease(id, "transient retry");
      }
      return;
    }

    String message = failure.getMessage()
        + " Retries exhausted after " + job.attempts() + " attempt(s). Transient history: "
        + history.stream()
            .map(BundleJobTransientFailure::describe)
            .collect(Collectors.joining("; "))
        + ".";
    log.error("Bundle job {} failed with {} and exhausted its {} attempt(s)", id, failure.code(),
        job.attempts());
    if (repository.markFailed(id, failure.code(), message,
        json.writeDocumentFailures(failure.documentFailures()), json.writeHistory(history),
        workerId)) {
      emit(id, BundleJobState.FAILED, 0, total);
    } else {
      logLostLease(id, "exhausted failure");
    }
  }

  private void failTerminally(UUID id, BundleErrorCode code, String message,
      List<DocumentFailure> documentFailures, int total) {
    if (repository.markFailed(id, code, message, json.writeDocumentFailures(documentFailures),
        null, workerId)) {
      emit(id, BundleJobState.FAILED, 0, total);
    } else {
      logLostLease(id, "failure");
    }
  }

  private void logLostLease(UUID id, String write) {
    log.warn("Bundle job {} {} was not recorded: this worker ({}) no longer holds the lease "
        + "(current owner: {}). The lease holder's state stands.",
        id, write, workerId, repository.leaseOwnerOf(id));
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
