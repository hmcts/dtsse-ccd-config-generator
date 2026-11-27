package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocRequestJson;

/**
 * Runs queued jobs. The auto-configuration starts one unless
 * {@code ccd.doc-toolkit.jobs.worker.enabled} is false, so a service can run its workers in a
 * separate deployment from the instances handling CCD callbacks.
 *
 * <p>Each poll claims what the worker has room for under a ten-minute lease, renders, uploads the
 * result and calls the {@link DocJobHandler} in one transaction with the worker's own
 * bookkeeping. A worker that dies mid-job, or takes longer than its lease, loses the job to
 * another worker; whatever it finishes afterwards is refused, as it no longer holds the job.
 */
public final class DocJobWorker implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(DocJobWorker.class);

  /**
   * How the worker runs.
   *
   * @param maxAttempts how many times a job is tried
   * @param firstRetryDelay the wait before the second attempt; it doubles each time, up to
   *     {@code maxRetryDelay}
   */
  public record Settings(Duration pollDelay, int maxConcurrentRenders, Duration lease,
      int maxAttempts, Duration firstRetryDelay, Duration maxRetryDelay) {

    /** One-second polls, two renders at a time, ten-minute leases, three attempts. */
    public static Settings defaults() {
      return new Settings(Duration.ofSeconds(1), 2, Duration.ofMinutes(10), 3,
          Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    /** These settings with a different poll delay. */
    public Settings withPollDelay(Duration delay) {
      return new Settings(delay, maxConcurrentRenders, lease, maxAttempts, firstRetryDelay,
          maxRetryDelay);
    }

    /** These settings with a different number of concurrent renders. */
    public Settings withMaxConcurrentRenders(int renders) {
      return new Settings(pollDelay, renders, lease, maxAttempts, firstRetryDelay, maxRetryDelay);
    }
  }

  private final DocJobRepository repository;
  private final DocToolkit toolkit;
  private final DocJobHandler handler;
  private final TransactionTemplate transactions;
  private final Settings settings;
  private final ExecutorService renders;
  private final ScheduledExecutorService timers;
  private final AtomicInteger inFlight = new AtomicInteger();

  /** Created by the auto-configuration. */
  public DocJobWorker(DocJobRepository repository, DocToolkit toolkit, DocJobHandler handler,
      TransactionTemplate transactions, Settings settings) {
    this.repository = Validate.requireNonNull(repository, "repository");
    this.toolkit = Validate.requireNonNull(toolkit, "toolkit");
    this.handler = Validate.requireNonNull(handler, "handler");
    this.transactions = Validate.requireNonNull(transactions, "transactions");
    this.settings = Validate.requireNonNull(settings, "settings");
    AtomicInteger threads = new AtomicInteger();
    this.renders = Executors.newFixedThreadPool(settings.maxConcurrentRenders(), task -> {
      Thread thread = new Thread(task, "doc-job-render-" + threads.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
    this.timers = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "doc-job-poller");
      thread.setDaemon(true);
      return thread;
    });
  }

  /** Starts polling. */
  public void start() {
    long delay = settings.pollDelay().toMillis();
    timers.scheduleWithFixedDelay(() -> {
      try {
        poll();
      } catch (RuntimeException e) {
        log.error("Polling for doc jobs failed", e);
      }
    }, delay, delay, TimeUnit.MILLISECONDS);
  }

  /** Stops polling and lets running jobs finish; an unfinished job is retried by another worker. */
  @Override
  public void close() {
    timers.shutdownNow();
    renders.shutdown();
  }

  /** Gives up crashed jobs, then claims as many as there is room for and runs them. */
  public void poll() {
    for (DocJobRepository.Claimed crashed : repository.crashed(settings.maxAttempts())) {
      fail(crashed, "CRASHED", "The job was claimed " + crashed.attempts() + " time(s) and each"
          + " worker stopped before finishing it. The render most likely ran out of memory; see"
          + " the service logs.", List.of());
    }
    int room = settings.maxConcurrentRenders() - inFlight.get();
    if (room <= 0) {
      return;
    }
    for (DocJobRepository.Claimed claimed : repository.claim(room, settings.lease(),
        settings.maxAttempts())) {
      inFlight.incrementAndGet();
      try {
        renders.execute(() -> {
          try {
            run(claimed);
          } catch (RuntimeException e) {
            log.error("Doc job {} failed unexpectedly", claimed.job(), e);
          } finally {
            inFlight.decrementAndGet();
          }
        });
      } catch (RejectedExecutionException e) {
        inFlight.decrementAndGet();
        log.warn("Doc job {} was not started; another worker takes it when its lease expires",
            claimed.job());
      }
    }
  }

  /** The worker no longer holds the job at the revision it rendered. */
  private static final class NotHeld extends RuntimeException {
    NotHeld() {
      super(null, null, false, false);
    }
  }

  private void run(DocJobRepository.Claimed claimed) {
    DocJob job = claimed.job();
    try (MDC.MDCCloseable ignoredJob = MDC.putCloseable("docJobKey", job.key());
        MDC.MDCCloseable ignoredCase = MDC.putCloseable("caseReference",
            String.valueOf(job.caseReference()))) {
      runHeld(claimed);
    }
  }

  private void runHeld(DocJobRepository.Claimed claimed) {
    DocJob job = claimed.job();
    PdfRequest request;
    try {
      request = DocRequestJson.read(claimed.requestJson());
    } catch (DocRequestJson.Unreadable e) {
      fail(claimed, "UNREADABLE_REQUEST", "Doc job " + job + " cannot be read: " + e.getMessage(),
          List.of());
      return;
    }
    boolean finalAttempt = claimed.attempts() >= settings.maxAttempts();
    DocToolkit.Stored stored;
    try {
      stored = toolkit.renderAndStore(request, job.caseReference());
    } catch (DocToolkitException e) {
      if (e.isTransient() && !finalAttempt) {
        retry(claimed, e.getMessage());
      } else {
        fail(claimed, e.reason().name(), e.getMessage(), e.failures());
      }
      return;
    } catch (RuntimeException e) {
      log.error("Doc job {}: rendering failed unexpectedly", job, e);
      if (!finalAttempt) {
        retry(claimed, e.toString());
      } else {
        fail(claimed, "RENDER_FAILED", "Rendering failed: " + e, List.of());
      }
      return;
    }
    complete(claimed, stored);
  }

  /**
   * Takes the job off the queue, records what the service is given, hands it the document and
   * attaches it to the case, in one transaction holding the case lock, so no case event runs on the
   * case meanwhile. Dequeuing comes first: if the worker no longer holds the job at the revision it
   * rendered, nothing else happens. A failure rolls back the database writes. If CDAM attached
   * the upload before the transaction rolled back, it remains attached.
   */
  private void complete(DocJobRepository.Claimed claimed, DocToolkit.Stored stored) {
    DocJob job = claimed.job();
    UploadedDoc uploaded = stored.uploaded();
    try {
      transactions.executeWithoutResult(status -> {
        // The case lock first, as a case event takes it: the handler's writes may need the case's
        // rows, and an event holding them may be waiting to submit to this job.
        repository.lockCase(job.caseReference());
        if (!repository.dequeue(claimed)) {
          throw new NotHeld();
        }
        repository.rendered(claimed);
        try {
          handler.completed(job, uploaded);
        } catch (RuntimeException e) {
          throw e;
        } catch (Exception e) {
          throw new HandlerFailed(e);
        }
        toolkit.attach(stored, job.caseReference());
      });
    } catch (NotHeld notHeld) {
      log.info("Doc job {} was superseded or lost its lease; its result is handed to nobody", job);
      repository.release(claimed);
      return;
    } catch (RuntimeException e) {
      Throwable cause = e instanceof HandlerFailed ? e.getCause() : e;
      boolean attaching = e instanceof DocToolkitException;
      String what = attaching ? "attaching the document" : "the DocJobHandler";
      log.error("Doc job {}: {} failed", job, what, cause);
      if (claimed.attempts() < settings.maxAttempts()) {
        retry(claimed, what + " failed: " + cause);
      } else {
        fail(claimed, attaching ? "UPLOAD_FAILED" : "HANDLER_FAILED",
            what + " failed " + claimed.attempts() + " time(s). Last failure: " + cause,
            List.of());
      }
      return;
    }
    log.info("Doc job {} completed after {} attempt(s): {}", job, claimed.attempts(),
        uploaded.document().getUrl());
  }

  private void retry(DocJobRepository.Claimed claimed, String why) {
    long delay = settings.firstRetryDelay().toMillis();
    for (int attempt = 1; attempt < claimed.attempts(); attempt++) {
      delay = Math.min(delay * 2, settings.maxRetryDelay().toMillis());
    }
    Instant next = Instant.now().plusMillis(delay);
    if (repository.retryAt(claimed, next)) {
      log.warn("Doc job {} attempt {} failed and is retried at {}: {}", claimed.job(),
          claimed.attempts(), next, why);
    } else {
      repository.release(claimed);
    }
  }

  /**
   * Gives the job up and tells the service, in one transaction holding the case lock, as completing
   * does. What the service was last given stays recorded, so submitting the request again retries
   * it. A handler that throws is logged and the job given up without it. Anything else that fails,
   * such as taking the case lock, leaves the job for another worker once the lease expires.
   */
  private void fail(DocJobRepository.Claimed claimed, String reason, String message,
      List<DocFailure> failures) {
    DocJob job = claimed.job();
    DocJobFailure failure = new DocJobFailure(reason, message, failures);
    boolean failed;
    try {
      failed = Boolean.TRUE.equals(transactions.execute(status -> {
        repository.lockCase(job.caseReference());
        if (!repository.dequeue(claimed)) {
          return false;
        }
        try {
          handler.failed(job, failure);
        } catch (Exception e) {
          throw new HandlerFailed(e);
        }
        return true;
      }));
    } catch (HandlerFailed e) {
      log.error("Doc job {}: the DocJobHandler could not be told it failed; giving it up without",
          job, e.getCause());
      failed = repository.dequeue(claimed);
    } catch (RuntimeException e) {
      log.error("Doc job {} ({}) could not be given up; another worker takes it when its lease"
          + " expires", job, reason, e);
      return;
    }
    if (failed) {
      log.error("Doc job {} failed ({}): {}", job, reason, message);
    } else {
      // Superseded: the newer request runs instead, and this failure is nobody's concern.
      repository.release(claimed);
    }
  }

  /** A checked exception from the handler, carried out of the transaction callback. */
  private static final class HandlerFailed extends RuntimeException {
    HandlerFailed(Exception cause) {
      super(cause);
    }
  }
}
