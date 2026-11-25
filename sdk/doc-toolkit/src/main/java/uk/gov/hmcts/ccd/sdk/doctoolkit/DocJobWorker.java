package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocRequestJson;

/**
 * Runs queued jobs. The auto-configuration starts one unless
 * {@code ccd.doc-toolkit.jobs.worker.enabled} is false, so a service can run its workers in a
 * separate deployment from the instances handling CCD callbacks.
 *
 * <p>Each poll claims what the worker has room for under a lease, renders and uploads each job,
 * then calls the {@link DocJobHandler}. A worker that dies mid-job loses its lease, and another
 * worker picks the job up.
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
  }

  private final DocJobRepository repository;
  private final DocToolkit toolkit;
  private final DocJobHandler handler;
  private final Settings settings;
  private final ExecutorService renders;
  private final ScheduledExecutorService poller;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final String workerId = "doc-job-worker-" + UUID.randomUUID();

  /** Created by the auto-configuration. */
  public DocJobWorker(DocJobRepository repository, DocToolkit toolkit, DocJobHandler handler,
      Settings settings) {
    this.repository = Validate.requireNonNull(repository, "repository");
    this.toolkit = Validate.requireNonNull(toolkit, "toolkit");
    this.handler = Validate.requireNonNull(handler, "handler");
    this.settings = Validate.requireNonNull(settings, "settings");
    AtomicInteger threads = new AtomicInteger();
    this.renders = Executors.newFixedThreadPool(settings.maxConcurrentRenders(), task -> {
      Thread thread = new Thread(task, "doc-job-render-" + threads.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
    this.poller = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "doc-job-poller");
      thread.setDaemon(true);
      return thread;
    });
  }

  /** Starts polling. */
  public void start() {
    long delay = settings.pollDelay().toMillis();
    poller.scheduleWithFixedDelay(() -> {
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
    poller.shutdownNow();
    renders.shutdown();
  }

  /** Fails crashed jobs, then claims as many as there is room for and runs them. */
  public void poll() {
    for (DocJob crashed : repository.failCrashed(settings.maxAttempts())) {
      log.error("Doc job {} failed: {}", crashed.id(),
          crashed.failure().map(DocJob.Failure::message).orElse(""));
      notifyFailed(crashed);
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

  private void run(DocJobRepository.Claimed claimed) {
    DocJob job = claimed.job();
    try (MDC.MDCCloseable ignored = MDC.putCloseable("docJobId", job.id().toString())) {
      DocRequest request;
      try {
        request = DocRequestJson.read(claimed.requestJson());
      } catch (DocRequestJson.Unreadable e) {
        fail(claimed, "UNREADABLE_REQUEST", "Doc job " + job.id() + " cannot be read: "
            + e.getMessage(), List.of());
        return;
      }
      UploadedDoc uploaded;
      if (claimed.resultJson() != null) {
        // Rendered and uploaded on an earlier attempt; only the handler is left.
        uploaded = DocRequestJson.readResult(claimed.resultJson());
      } else {
        boolean finalAttempt = job.attempts() >= settings.maxAttempts();
        try {
          uploaded = toolkit.renderAndUpload(request, job.caseId(), job.eventId(), finalAttempt);
        } catch (DocToolkitException e) {
          if (e.isTransient() && !finalAttempt) {
            retry(claimed, e.getMessage());
          } else {
            fail(claimed, e.reason().name(), e.getMessage(), e.failures());
          }
          return;
        }
        if (!repository.recordResult(job.id(), DocRequestJson.writeResult(uploaded),
            claimed.leaseOwner())) {
          log.warn("Doc job {} lost its lease while rendering; another worker has it", job.id());
          return;
        }
      }
      try {
        handler.completed(withResult(job, uploaded), request, uploaded);
      } catch (Exception e) {
        log.error("Doc job {}: the DocJobHandler failed", job.id(), e);
        if (job.attempts() < settings.maxAttempts()) {
          retry(claimed, "the DocJobHandler failed: " + e);
        } else {
          fail(claimed, "HANDLER_FAILED", "The DocJobHandler failed " + job.attempts()
              + " time(s); the document is stored at " + uploaded.document().getUrl()
              + ". Last failure: " + e, List.of());
        }
        return;
      }
      if (repository.markCompleted(job.id(), claimed.leaseOwner())) {
        log.info("Doc job {} completed after {} attempt(s): {}", job.id(), job.attempts(),
            uploaded.document().getUrl());
      }
    }
  }

  private void retry(DocJobRepository.Claimed claimed, String why) {
    DocJob job = claimed.job();
    long delay = settings.firstRetryDelay().toMillis();
    for (int attempt = 1; attempt < job.attempts(); attempt++) {
      delay = Math.min(delay * 2, settings.maxRetryDelay().toMillis());
    }
    Instant next = Instant.now().plusMillis(delay);
    if (repository.requeue(job.id(), next, claimed.leaseOwner())) {
      log.warn("Doc job {} attempt {} failed and is retried at {}: {}", job.id(), job.attempts(),
          next, why);
    }
  }

  private void fail(DocJobRepository.Claimed claimed, String reason, String message,
      List<DocFailure> failures) {
    UUID id = claimed.job().id();
    if (repository.markFailed(id, reason, message, DocRequestJson.writeFailures(failures),
        claimed.leaseOwner())) {
      log.error("Doc job {} failed ({}): {}", id, reason, message);
      repository.find(id).ifPresent(this::notifyFailed);
    }
  }

  private void notifyFailed(DocJob job) {
    try {
      handler.failed(job);
    } catch (Exception e) {
      log.error("Doc job {}: the DocJobHandler's failed() threw", job.id(), e);
    }
  }

  private static DocJob withResult(DocJob job, UploadedDoc uploaded) {
    return new DocJob(job.id(), job.state(), job.caseId(), job.eventId(), job.attempts(),
        job.submittedAt(), job.updatedAt(), java.util.Optional.of(uploaded), job.failure());
  }
}
