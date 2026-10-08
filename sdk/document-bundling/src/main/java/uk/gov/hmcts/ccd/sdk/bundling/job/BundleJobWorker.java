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
 * The scheduled worker: each poll fails stale jobs that exhausted their attempts, claims a batch
 * of executable rows under a lease, and for each one runs the document selector, renders, hands
 * the open result to the completion handler and records the terminal state. Terminal writes are
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
    for (UUID exhausted : repository.failExhaustedStaleJobs(retryPolicy.maxAttempts())) {
      log.error("Bundle job {} exhausted its {} attempt(s) without recording a result; FAILED",
          exhausted, retryPolicy.maxAttempts());
      emit(exhausted, BundleJobState.FAILED, 0, 0);
    }
    for (ClaimedBundleJob claimed : repository.claim(toClaim, workerId, leaseDuration,
        retryPolicy.maxAttempts())) {
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
    BundleRequest request;
    BundleExecutionContext context;
    BundleJobContext jobContext;
    try {
      if (claimed.requestVersion() > BundleJobJson.REQUEST_VERSION) {
        throw new BundleJobJson.Unreadable("the job was stored with request version "
            + claimed.requestVersion() + " but this worker reads up to version "
            + BundleJobJson.REQUEST_VERSION, null);
      }
      Optional<BundleRequest> submitted = claimed.requestJson() == null ? Optional.empty()
          : Optional.of(json.read(claimed.requestJson(), BundleRequest.class, "bundle request"));
      context = json.read(claimed.executionContextJson(), BundleExecutionContext.class,
          "execution context");
      jobContext = new BundleJobContext(id, submitted,
          json.readParameters(claimed.selectorParametersJson()), context);
      request = selector.select(jobContext);
    } catch (BundleJobJson.Unreadable e) {
      log.error("Bundle job {} is unreadable by this worker: {}", id, e.getMessage(), e);
      failTerminally(id, BundleErrorCode.JOB_REQUEST_UNREADABLE, "Bundle job " + id
          + " could not be read by this worker: " + e.getMessage()
          + ". Re-submit the bundle under a new external id.", List.of(), 0);
      return;
    } catch (BundleGenerationException e) {
      handleGenerationFailure(claimed, e, 0);
      return;
    } catch (RuntimeException e) {
      log.error("Bundle job {} request could not be compiled for execution", id, e);
      failTerminally(id, BundleErrorCode.JOB_REQUEST_UNREADABLE, "The bundle request could not be "
          + "compiled for execution; the selector threw " + e.getClass().getName() + LOG_ONLY,
          List.of(), 0);
      return;
    }

    int total = request.allDocuments().size();
    emit(id, BundleJobState.IN_PROGRESS, 0, total);
    try (BundleResult result = renderer.render(request, context)) {
      String summary;
      try {
        Object value = completionHandler.onCompleted(job, jobContext, request, result);
        summary = value == null ? null : json.write(value);
      } catch (Exception e) {
        log.error("Bundle job {} rendered but its completion handler failed", id, e);
        failTerminally(id, BundleErrorCode.COMPLETION_FAILED, "The bundle rendered but the "
            + "completion handler threw " + e.getClass().getName() + LOG_ONLY, List.of(), total);
        return;
      }
      BundleJobState terminal = result.outcome() == BundleOutcome.COMPLETED_WITH_WARNINGS
          ? BundleJobState.COMPLETED_WITH_WARNINGS : BundleJobState.COMPLETED;
      log.info("Bundle job {} completed as {} after {} attempt(s)", id, terminal, job.attempts());
      record(repository.markCompleted(id, terminal, summary, workerId), id, terminal, total, total,
          "completion");
    } catch (BundleGenerationException e) {
      handleGenerationFailure(claimed, e, total);
    } catch (RuntimeException e) {
      log.error("Bundle job {} failed with an untyped renderer error", id, e);
      failTerminally(id, BundleErrorCode.ASSEMBLY_FAILED,
          "Unexpected renderer failure of type " + e.getClass().getName() + LOG_ONLY,
          List.of(), total);
    }
  }

  private void handleGenerationFailure(ClaimedBundleJob claimed, BundleGenerationException failure,
      int total) {
    UUID id = claimed.job().externalId();
    int attempts = claimed.job().attempts();
    if (!retryPolicy.isTransient(failure)) {
      log.error("Bundle job {} failed with non-retryable {}: {}", id, failure.code(),
          failure.getMessage());
      failTerminally(id, failure.code(), failure.getMessage(), failure.documentFailures(), total);
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
      record(repository.requeueForRetry(id, nextAttemptAt.get(), json.write(history), workerId),
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
        json.write(failure.documentFailures()), json.write(history), workerId),
        id, BundleJobState.FAILED, 0, total, "exhausted failure");
  }

  private void failTerminally(UUID id, BundleErrorCode code, String message,
      List<DocumentFailure> documentFailures, int total) {
    record(repository.markFailed(id, code, message, json.write(documentFailures), null, workerId),
        id, BundleJobState.FAILED, 0, total, "failure");
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
