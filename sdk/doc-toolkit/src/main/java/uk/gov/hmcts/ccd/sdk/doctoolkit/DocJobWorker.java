package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
 * <p>Each poll claims what the worker has room for under a lease, renews the lease while
 * rendering, uploads the result and calls the {@link DocJobHandler} in one transaction with the
 * worker's own bookkeeping. A worker that dies mid-job loses its lease, and another worker picks
 * the job up.
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

    /** One-second polls, two renders at a time, five-minute leases, three attempts. */
    public static Settings defaults() {
      return new Settings(Duration.ofSeconds(1), 2, Duration.ofMinutes(5), 3,
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
  private final String workerId = "doc-job-worker-" + UUID.randomUUID();

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

  /** Fails crashed jobs, then claims as many as there is room for and runs them. */
  public void poll() {
    for (DocJob crashed : repository.failCrashed(settings.maxAttempts())) {
      log.error("Doc job {} for case {} failed: {}", crashed.id(), crashed.caseReference(),
          crashed.failure().map(DocJob.Failure::message).orElse(""));
    }
    int room = settings.maxConcurrentRenders() - inFlight.get();
    if (room <= 0) {
      return;
    }
    // A lease owner per poll, so a job this worker reclaims after its own lease expired is new.
    String leaseOwner = workerId + "/" + UUID.randomUUID();
    for (DocJobRepository.Claimed claimed : repository.claim(room, leaseOwner, settings.lease(),
        settings.maxAttempts())) {
      inFlight.incrementAndGet();
      try {
        renders.execute(() -> {
          try {
            run(claimed);
          } catch (RuntimeException e) {
            log.error("Doc job {} failed unexpectedly", claimed.job().id(), e);
          } finally {
            inFlight.decrementAndGet();
          }
        });
      } catch (RejectedExecutionException e) {
        inFlight.decrementAndGet();
        log.warn("Doc job {} was not started; another worker takes it when its lease expires",
            claimed.job().id());
      }
    }
  }

  /** The worker no longer holds the job, or its request was superseded. */
  private static final class NotHeld extends RuntimeException {
    NotHeld() {
      super(null, null, false, false);
    }
  }

  private void run(DocJobRepository.Claimed claimed) {
    DocJob job = claimed.job();
    // The lease is renewed until the job is finished, handler included, so no other worker
    // takes it over while this one is still working on it.
    ScheduledFuture<?> renewal = renewLease(claimed);
    try (MDC.MDCCloseable ignoredJob = MDC.putCloseable("docJobId", job.id().toString());
        MDC.MDCCloseable ignoredCase = MDC.putCloseable("caseReference",
            String.valueOf(job.caseReference()))) {
      runHeld(claimed);
    } finally {
      renewal.cancel(false);
    }
  }

  private void runHeld(DocJobRepository.Claimed claimed) {
    DocJob job = claimed.job();
    PdfRequest request;
    try {
      request = DocRequestJson.read(claimed.requestJson());
    } catch (DocRequestJson.Unreadable e) {
      fail(claimed, "UNREADABLE_REQUEST", "Doc job " + job.id() + " cannot be read: "
          + e.getMessage(), List.of());
      return;
    }
    boolean finalAttempt = job.attempts() >= settings.maxAttempts();
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
      log.error("Doc job {}: rendering failed unexpectedly", job.id(), e);
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
   * Finishes the job, hands the document to the service and attaches it to the case, in one
   * transaction. Finishing comes first: if the worker no longer holds the job, nothing else
   * happens, and once it has finished, the job's row stays locked, so no case event supersedes
   * it before the transaction ends. Anything that fails leaves the upload unattached, so it
   * expires in CDAM, and the job is rendered again.
   */
  private void complete(DocJobRepository.Claimed claimed, DocToolkit.Stored stored) {
    DocJob job = claimed.job();
    UploadedDoc uploaded = stored.uploaded();
    try {
      transactions.executeWithoutResult(status -> {
        if (!repository.finish(job.id(), claimed.leaseOwner(), job.revision())) {
          throw new NotHeld();
        }
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
      log.info("Doc job {} was superseded or lost its lease; its result is handed to nobody",
          job.id());
      repository.releaseSuperseded(job.id(), claimed.leaseOwner());
      return;
    } catch (RuntimeException e) {
      Throwable cause = e instanceof HandlerFailed ? e.getCause() : e;
      boolean attaching = e instanceof DocToolkitException;
      String what = attaching ? "attaching the document" : "the DocJobHandler";
      log.error("Doc job {}: {} failed", job.id(), what, cause);
      if (job.attempts() < settings.maxAttempts()) {
        retry(claimed, what + " failed: " + cause);
      } else {
        fail(claimed, attaching ? "UPLOAD_FAILED" : "HANDLER_FAILED",
            what + " failed " + job.attempts() + " time(s). Last failure: " + cause, List.of());
      }
      return;
    }
    log.info("Doc job {} completed after {} attempt(s): {}", job.id(), job.attempts(),
        uploaded.document().getUrl());
  }

  private ScheduledFuture<?> renewLease(DocJobRepository.Claimed claimed) {
    long every = Math.max(1, settings.lease().toMillis() / 3);
    return timers.scheduleAtFixedRate(() -> {
      try {
        repository.renewLease(claimed.job().id(), claimed.leaseOwner(), settings.lease());
      } catch (RuntimeException e) {
        log.warn("Could not renew the lease on doc job {}", claimed.job().id(), e);
      }
    }, every, every, TimeUnit.MILLISECONDS);
  }

  private void retry(DocJobRepository.Claimed claimed, String why) {
    DocJob job = claimed.job();
    long delay = settings.firstRetryDelay().toMillis();
    for (int attempt = 1; attempt < job.attempts(); attempt++) {
      delay = Math.min(delay * 2, settings.maxRetryDelay().toMillis());
    }
    Instant next = Instant.now().plusMillis(delay);
    if (repository.requeue(job.id(), claimed.leaseOwner(), job.revision(), next)) {
      log.warn("Doc job {} attempt {} failed and is retried at {}: {}", job.id(), job.attempts(),
          next, why);
    } else {
      repository.releaseSuperseded(job.id(), claimed.leaseOwner());
    }
  }

  private void fail(DocJobRepository.Claimed claimed, String reason, String message,
      List<DocFailure> failures) {
    DocJob job = claimed.job();
    if (repository.markFailed(job.id(), claimed.leaseOwner(), job.revision(), reason, message,
        DocRequestJson.writeFailures(failures))) {
      log.error("Doc job {} for case {} failed ({}): {}", job.id(), job.caseReference(), reason,
          message);
    } else {
      // Superseded: the newer request runs instead, and this failure is nobody's concern.
      repository.releaseSuperseded(job.id(), claimed.leaseOwner());
    }
  }

  /** A checked exception from the handler, carried out of the transaction callback. */
  private static final class HandlerFailed extends RuntimeException {
    HandlerFailed(Exception cause) {
      super(cause);
    }
  }
}
