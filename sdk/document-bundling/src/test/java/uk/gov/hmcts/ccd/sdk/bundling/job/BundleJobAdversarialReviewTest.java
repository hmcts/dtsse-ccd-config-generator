package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;

/**
 * Adversarial review counter-examples for the durable job runner. Each finding's proof test
 * encodes the DESIRED behaviour; all findings are now fixed and every test runs enabled, guarding
 * the fix. The FINDING comments record the original defect and its resolution.
 */
@Testcontainers
class BundleJobAdversarialReviewTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static NamedParameterJdbcTemplate jdbc;
  private static BundleJobRepository repository;
  private static OutboxBundleJobService service;
  private static DataSourceTransactionManager transactionManager;

  private final BundleJobJson json = new BundleJobJson();

  @BeforeAll
  static void createSchema() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new NamedParameterJdbcTemplate(dataSource);
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection,
          new ClassPathResource("document-bundling-db/migration/V0001__ccd_bundle_job.sql"));
    }
    repository = new BundleJobRepository(jdbc);
    service = new OutboxBundleJobService(repository);
    transactionManager = new DataSourceTransactionManager(dataSource);
  }

  @BeforeEach
  void cleanTable() {
    jdbc.update("delete from ccd_bundle_job", Map.of());
  }

  private String column(UUID externalId, String column) {
    return jdbc.queryForObject(
        "select " + column + "::text from ccd_bundle_job where external_id = :id",
        Map.of("id", externalId), String.class);
  }

  private void expireLease(UUID externalId) {
    jdbc.update("update ccd_bundle_job set lease_expires_at = now() - interval '1 second' "
        + "where external_id = :id", Map.of("id", externalId));
  }

  private static BundleJobRetryPolicy quickRetries(int maxAttempts) {
    return new BundleJobRetryPolicy(maxAttempts, Duration.ofMillis(1), 2.0, Duration.ofMillis(2));
  }

  // ---------------------------------------------------------------------------------------------
  // FINDING 1: terminal writes carry no lease-owner or state guard. markCompleted, markFailed
  // and requeueForRetry update "where external_id = :externalId" unconditionally
  // (BundleJobRepository.java:168-236), so a worker whose lease has expired and whose job has
  // been reclaimed by another worker silently clobbers the reclaimer's state. A COMPLETED
  // (published!) job can be dragged back to QUEUED and rendered/published again.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aStaleWorkersCompletionMustNotOverwriteAReclaimedJob() {
    // FINDING-1 (fixed): terminal writes are lease-guarded.
    UUID id = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());

    // Worker A claims and stalls past its lease.
    assertThat(repository.claim(1, "worker-a", Duration.ofMillis(50), 3)).hasSize(1);
    expireLease(id);

    // Worker B legitimately reclaims; it now owns the job and is rendering.
    assertThat(repository.claim(1, "worker-b", Duration.ofMinutes(5), 3)).hasSize(1);
    assertThat(column(id, "lease_owner")).isEqualTo("worker-b");

    // Worker A's render finally finishes and it records completion — but A lost the lease.
    // Desired: the write is rejected; B's claim stands.
    String resultJson = json.writeBundle(BundleJobFixtures.resultFor(
        BundleJobFixtures.simpleRequest(id), BundleOutcome.COMPLETED).output());
    assertThat(repository.markCompleted(id, BundleJobState.COMPLETED, resultJson, "worker-a"))
        .isFalse();

    assertThat(column(id, "state")).isEqualTo("RESOLVING");
    assertThat(column(id, "lease_owner")).isEqualTo("worker-b");
  }

  @Test
  void aStaleWorkersRetryMustNotRequeueACompletedJob() {
    // FINDING-1 (fixed): requeueForRetry is lease- and state-guarded.
    UUID id = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());

    // Worker A claims and stalls past its lease; worker B reclaims, renders, completes and
    // publishes the bundle.
    assertThat(repository.claim(1, "worker-a", Duration.ofMillis(50), 3)).hasSize(1);
    expireLease(id);
    assertThat(repository.claim(1, "worker-b", Duration.ofMinutes(5), 3)).hasSize(1);
    String resultJson = json.writeBundle(BundleJobFixtures.resultFor(
        BundleJobFixtures.simpleRequest(id), BundleOutcome.COMPLETED).output());
    assertThat(repository.markCompleted(id, BundleJobState.COMPLETED, resultJson, "worker-b"))
        .isTrue();
    assertThat(column(id, "state")).isEqualTo("COMPLETED");

    // Worker A's stalled render now fails transiently and A records a retry.
    // Desired: the terminal state stands; the published bundle is never re-queued.
    assertThat(repository.requeueForRetry(id, Instant.now(), "[]", "worker-a")).isFalse();

    assertThat(column(id, "state")).isEqualTo("COMPLETED");
    // And the job must not be claimable again — otherwise a third render republishes.
    assertThat(repository.claim(1, "worker-c", Duration.ofMinutes(5), 3)).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // FINDING 2 (fixed by the FINDING-1 lease guards): lease expiry during an in-flight render
  // still causes a second render — that duplicate WORK is inherent to lease-based recovery, and
  // the operational mitigation is the documented invariant that the lease duration must exceed
  // the renderer's enforced timeout. What must never happen, and no longer can, is a double or
  // conflicting terminal write: only the current lease holder's completion is recorded, and the
  // lease loser detects the rejected write instead of blindly recording a publish.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aRenderOutlivingItsLeaseRecordsOnlyTheLeaseHoldersPublish() throws Exception {
    UUID id = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());

    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    FakeBundleRenderer renderer = new FakeBundleRenderer()
        .onNextRender(request -> {
          firstStarted.countDown();
          try {
            releaseFirst.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          BundleResult result = BundleJobFixtures.resultFor(request, BundleOutcome.COMPLETED);
          result.output().setDescription("stale-worker-render");
          return result;
        })
        .onNextRender(request -> {
          BundleResult result = BundleJobFixtures.resultFor(request, BundleOutcome.COMPLETED);
          result.output().setDescription("lease-holder-render");
          return result;
        });

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      BundleJobWorker slowWorker = new BundleJobWorker(repository, renderer,
          BundleDocumentSelector.asSubmitted(), quickRetries(3), List.of(), pool, 1, 1,
          Duration.ofMillis(150));
      slowWorker.poll();
      assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();

      // The first render is still running when its lease expires...
      expireLease(id);

      // ...so a second worker reclaims and renders the same job to completion.
      BundleJobWorker reclaimer = new BundleJobWorker(repository, renderer,
          BundleDocumentSelector.asSubmitted(), quickRetries(3), List.of(), Runnable::run, 1, 1,
          Duration.ofMinutes(5));
      reclaimer.poll();
      assertThat(column(id, "state")).isEqualTo("COMPLETED");

      // Now the first render finishes and tries to record its own completion.
      releaseFirst.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

      // The duplicate render happened (inherent to lease recovery), but exactly one publish is
      // recorded and it is the lease holder's; the stale worker's write was rejected.
      assertThat(renderer.renders).hasSize(2);
      assertThat(column(id, "state")).isEqualTo("COMPLETED");
      assertThat(column(id, "result")).contains("lease-holder-render");
      assertThat(column(id, "result")).doesNotContain("stale-worker-render");
      assertThat(column(id, "attempts")).isEqualTo("2");
    } finally {
      pool.shutdownNow();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FINDING 3: a rejected dispatch permanently leaks in-flight capacity. poll() increments
  // inFlight before executor.execute (BundleJobWorker.java:134-144); if execute throws
  // RejectedExecutionException the decrement (inside the task) never runs, the claimed row is
  // stranded on its lease, and — with maxConcurrentRenders small — the worker's capacity is
  // exhausted forever: every later poll computes capacity <= 0 and claims nothing, even after
  // the executor recovers and the lease expires.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aWorkerMustRecoverAfterItsExecutorRejectsADispatch() throws Exception {
    // FINDING-3 (fixed): a rejected dispatch releases the claim immediately — lease cleared,
    // the attempt handed back, in-flight capacity restored — and poll survives it.
    UUID id = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());

    AtomicBoolean reject = new AtomicBoolean(true);
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    Executor flaky = task -> {
      if (reject.get()) {
        throw new RejectedExecutionException("render queue saturated");
      }
      delegate.execute(task);
    };
    try {
      BundleJobWorker worker = new BundleJobWorker(repository, new FakeBundleRenderer(),
          BundleDocumentSelector.asSubmitted(), quickRetries(3), List.of(), flaky, 1, 1,
          Duration.ofMillis(100));

      worker.poll();

      // The undispatched claim was released, not stranded on a lease or a burned attempt.
      assertThat(column(id, "state")).isEqualTo("QUEUED");
      assertThat(column(id, "lease_owner")).isNull();
      assertThat(column(id, "attempts")).isEqualTo("0");

      // The executor recovers; the worker's capacity was never leaked, so it claims and
      // completes the job.
      reject.set(false);
      await().atMost(Duration.ofSeconds(5)).until(() -> {
        worker.poll();
        return "COMPLETED".equals(column(id, "state"));
      });
    } finally {
      delegate.shutdownNow();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FINDING 4: the claim predicate has no attempts bound (BundleJobRepository.java:124-132),
  // unlike the mirrored task-management module (TaskOutboxRepository.findPending:
  // "attempt_count < maxAttempts"). A job whose render crashes the JVM, throws an Error, or is
  // OOM-killed every time never reaches the retry policy — it is reclaimed on every lease
  // expiry, forever. attempts grows without bound and the poison job renders eternally.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aJobFarBeyondTheRetryBoundMustNotBeReclaimed() {
    // FINDING-4 (fixed): the claim predicate carries the attempt ceiling, and the worker's
    // reaper sweep terminally fails crash-looped stale jobs with a clear message.
    UUID id = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());
    assertThat(repository.claim(1, "worker-a", Duration.ofMillis(50), 3)).hasSize(1);

    // Simulate a long history of crashed attempts, far past any retry policy's bound.
    jdbc.update("update ccd_bundle_job set attempts = 99 where external_id = :id",
        Map.of("id", id));
    expireLease(id);

    // The outbox stops re-running a job that has crashed 99 times.
    assertThat(repository.claim(1, "worker-b", Duration.ofMinutes(5), 3)).isEmpty();

    // A worker's poll sweeps it to a terminal, clearly-explained failure instead of leaving it
    // parked forever.
    BundleJobWorker worker = new BundleJobWorker(repository, new FakeBundleRenderer(),
        BundleDocumentSelector.asSubmitted(), quickRetries(3), List.of(), Runnable::run, 1, 1,
        Duration.ofMinutes(5));
    worker.poll();

    assertThat(column(id, "state")).isEqualTo("FAILED");
    assertThat(column(id, "failure_message")).contains("99").contains("exhausted");
    assertThat(column(id, "lease_owner")).isNull();
  }

  @Test
  void aRendererErrorDoesNotLeakWorkerCapacityAndLeavesTheJobToLeaseRecovery() throws Exception {
    // Enabled: documents what DOES hold. An Error (e.g. OutOfMemoryError) escaping the render
    // bypasses the terminal-state writes, but the finally block still restores in-flight
    // capacity, so the worker keeps processing other jobs; the Error job stays leased in
    // RESOLVING for stale-lease recovery (see FINDING-4 for why that recovery is unbounded).
    UUID poison = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(poison), BundleJobFixtures.context());
    Thread.sleep(5); // deterministic created_at ordering: poison claims first
    UUID healthy = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(healthy), BundleJobFixtures.context());

    // A custom Error stands in for OutOfMemoryError/NoClassDefFoundError: it takes the same
    // untyped-Throwable path through the worker without tripping Gradle's fatal-OOM handling.
    final class SimulatedRenderError extends Error {
      SimulatedRenderError() {
        super("simulated render Error (stands in for OutOfMemoryError)");
      }
    }
    FakeBundleRenderer renderer = new FakeBundleRenderer().onNextRender(request -> {
      throw new SimulatedRenderError();
    });
    // The worker's dispatch catches only RuntimeException (BundleJobWorker.java:136-141), so an
    // Error escapes to the pool thread's uncaught handler: unlogged by the worker, invisible
    // against the job id. The swallowing handler here keeps Gradle's test worker alive; in
    // production nothing installs one.
    ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable);
      thread.setUncaughtExceptionHandler((t, e) -> { });
      return thread;
    });
    try {
      BundleJobWorker worker = new BundleJobWorker(repository, renderer,
          BundleDocumentSelector.asSubmitted(), quickRetries(3), List.of(), pool, 1, 1,
          Duration.ofMinutes(5));

      worker.poll();
      // Capacity must come back after the Error so the healthy job still runs.
      await().atMost(Duration.ofSeconds(10)).until(() -> {
        worker.poll();
        return "COMPLETED".equals(column(healthy, "state"));
      });

      // The poison job was neither completed nor failed: it waits, leased, in RESOLVING.
      assertThat(column(poison, "state")).isEqualTo("RESOLVING");
      assertThat(column(poison, "lease_owner")).isNotNull();
    } finally {
      pool.shutdownNow();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FINDING 5: duplicate submission under REPEATABLE READ (or SERIALIZABLE) does not return the
  // existing job — verified against real PostgreSQL, the losing INSERT ... ON CONFLICT DO
  // NOTHING blocks on the winner's speculative index insert and then aborts with "could not
  // serialize access due to concurrent update" (SQLSTATE 40001, surfaced as Spring's
  // CannotAcquireLockException), killing the caller's whole transaction — typically the CCD
  // event that submitted the bundle. Under READ COMMITTED it would instead risk the
  // IllegalStateException in OutboxBundleJobService.existing (OutboxBundleJobService.java:67-70)
  // only if find() raced; the RR/serializable case is the concrete, reproducible failure.
  // ---------------------------------------------------------------------------------------------

  @Test
  void concurrentDuplicateSubmissionsUnderRepeatableReadBothReturnTheJob() throws Exception {
    // FINDING-5 (fixed): the losing submit catches the serialization failure and returns the
    // winner's committed job, read on a fresh connection outside the aborted transaction.
    // READ COMMITTED remains the documented supported isolation for submitters.
    UUID id = UUID.randomUUID();
    TransactionTemplate repeatableRead = new TransactionTemplate(transactionManager);
    repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

    CountDownLatch firstInserted = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<BundleJob> first = pool.submit(() -> repeatableRead.execute(status -> {
        BundleJob job =
            service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());
        firstInserted.countDown();
        try {
          Thread.sleep(700); // hold the transaction open so the second submit blocks on the index
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return job;
      }));
      assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();

      Future<BundleJob> second = pool.submit(() -> repeatableRead.execute(status ->
          service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context())));

      assertThat(first.get(20, TimeUnit.SECONDS).externalId()).isEqualTo(id);
      // Desired: the loser sees the winner's job (decision 7: "returns the existing job").
      assertThat(second.get(20, TimeUnit.SECONDS).externalId()).isEqualTo(id);
    } finally {
      pool.shutdownNow();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // FINDING 6: every selector failure is terminal REQUEST_INVALID (BundleJobWorker.java:181-186),
  // including a typed transient BundleGenerationException. A service using execution-time
  // selection queries CCD/case data inside the selector; one connection blip permanently fails
  // the job with a code that means "the request failed validation", and it is never retried.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aTypedTransientSelectorFailureMustRetryLikeAnyOtherTransientFailure() {
    // FINDING-6 (fixed): a BundleGenerationException from the selector takes the same
    // transient-versus-terminal path as a render failure.
    UUID id = UUID.randomUUID();
    service.submit(id, Map.of("caseReference", "1234"), BundleJobFixtures.context());

    BundleDocumentSelector flakySelector = context -> {
      throw new BundleGenerationException(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
          BundleStage.RESOLVE, "Case data store returned 503 while compiling the document list.",
          "Retry when the case data store recovers.", List.of());
    };
    BundleJobWorker worker = new BundleJobWorker(repository, new FakeBundleRenderer(),
        flakySelector, quickRetries(3), List.of(), Runnable::run, 1, 1, Duration.ofMinutes(5));

    worker.poll();

    // Desired: transient means requeued, not terminally failed as an invalid request.
    assertThat(column(id, "state")).isEqualTo("QUEUED");
    assertThat(column(id, "failure_code")).isNull();
  }

  // ---------------------------------------------------------------------------------------------
  // Enabled guards for properties that genuinely hold.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aLiveLeaseIsNeverReclaimedInAnyInProgressState() {
    // The claimable predicate excludes every in-progress state while the lease is unexpired.
    for (String state : List.of("RESOLVING", "CONVERTING", "ASSEMBLING", "STORING")) {
      UUID id = UUID.randomUUID();
      service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());
      assertThat(repository.claim(1, "worker-a", Duration.ofMinutes(5), 3)).hasSize(1);
      jdbc.update("update ccd_bundle_job set state = :state where external_id = :id",
          Map.of("state", state, "id", id));

      assertThat(repository.claim(5, "worker-b", Duration.ofMinutes(5), 3))
          .as("claim while %s under a live lease", state)
          .isEmpty();
      jdbc.update("delete from ccd_bundle_job where external_id = :id", Map.of("id", id));
    }
  }

  @Test
  void terminalStatesAreNeverClaimableEvenWithAStaleLookingRow() {
    // A terminal row with leftover lease/next-attempt values must never re-enter execution.
    for (String state : List.of("COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED")) {
      UUID id = UUID.randomUUID();
      service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());
      jdbc.update("update ccd_bundle_job set state = :state, "
              + "lease_owner = 'worker-x', lease_expires_at = now() - interval '1 hour', "
              + "next_attempt_at = now() - interval '1 hour' where external_id = :id",
          Map.of("state", state, "id", id));

      assertThat(repository.claim(5, "worker-b", Duration.ofMinutes(5), 3))
          .as("claim of a %s row", state)
          .isEmpty();
      jdbc.update("delete from ccd_bundle_job where external_id = :id", Map.of("id", id));
    }
  }

  @Test
  void aRequeuedJobIsNotClaimableBeforeItsNextAttemptTime() {
    UUID id = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());
    assertThat(repository.claim(1, "worker-a", Duration.ofMinutes(5), 3)).hasSize(1);
    assertThat(repository.requeueForRetry(
        id, Instant.now().plus(Duration.ofHours(1)), "[]", "worker-a")).isTrue();

    // The backoff is honoured by the claim predicate, not just recorded.
    assertThat(repository.claim(5, "worker-b", Duration.ofMinutes(5), 3)).isEmpty();
  }

  @Test
  void submittingTheSameExternalIdWithDifferentContentSilentlyKeepsTheOriginalRequest() {
    // Characterisation of decision 7's edge: the second submission's DIFFERENT content is
    // discarded without any signal to the caller — no log, no comparison, no flag on the
    // returned job. Reported as a finding for observability, but the returned job matching the
    // original submission is the documented contract, guarded here.
    UUID id = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());
    BundleJob second = service.submit(BundleJobFixtures.fullRequest(id),
        BundleExecutionContext.builder().caseReference("9999").build());

    assertThat(second.externalId()).isEqualTo(id);
    assertThat(column(id, "request")).contains("Hearing bundle");
    assertThat(column(id, "request")).doesNotContain("Final hearing bundle");
    assertThat(column(id, "execution_context")).doesNotContain("9999");
  }
}
