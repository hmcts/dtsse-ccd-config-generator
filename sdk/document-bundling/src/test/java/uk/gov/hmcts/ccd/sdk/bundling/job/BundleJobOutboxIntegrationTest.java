package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleDocumentStore;
import java.util.function.LongFunction;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocumentReason;
import uk.gov.hmcts.ccd.sdk.bundling.api.RenderAttempt;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@Testcontainers
class BundleJobOutboxIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");
  // What each test case's selection is, for the worker's selector to return.
  private static final Map<Long, BundleRequest> REQUESTS = new ConcurrentHashMap<>();
  // The CCD case each test's case name stands for.
  private static final Map<String, Long> CASES = new ConcurrentHashMap<>();
  private static final StoredBundleDocument STORED = stored();
  // The definitions every test worker has.
  private static final List<String> DEFINITIONS =
      List.of("test", "bundle", "hearing", "other", "added-in-the-next-release");
  private static final String INSERT_CASE = """
      insert into ccd.case_data (id, reference, jurisdiction, case_type_id, state, data,
        security_classification)
      values (:reference, :reference, 'Jurisdiction', 'CaseType', 'Open', '{}', 'PUBLIC')
      """;
  // The application's own migrations, which the runtime's strategy runs after the libraries'.
  private static final String CONSUMER_MIGRATIONS =
      "spring.flyway.locations=classpath:consumer-db/migration";

  private static DataSource dataSource;
  private static NamedParameterJdbcTemplate jdbc;
  private static BundleJobRepository repository;
  private static DataSourceTransactionManager transactions;
  private static TransactionTemplate tx;

  @TempDir
  Path tempDir;

  @BeforeAll
  static void migrateThroughTheSdkFlywayStrategy() {
    dataSource = dataSourceFor(POSTGRES.getDatabaseName());
    sdkMigrationRunner(dataSource).run(context -> assertThat(context).hasNotFailed());
    jdbc = new NamedParameterJdbcTemplate(dataSource);
    transactions = new DataSourceTransactionManager(dataSource);
    repository = new BundleJobRepository(jdbc, transactions);
    tx = new TransactionTemplate(transactions);
  }

  @BeforeEach
  void cleanTable() {
    // Deleting a case deletes its jobs and bundles.
    jdbc.update("delete from ccd.case_data", Map.of());
    CASES.clear();
  }

  @Test
  void liveJobsAreClaimedBeforeRefreshJobsAndALiveChangePromotesAWaitingRefresh() {
    caseRef("background");
    caseRef("hot");
    assertThat(refresh("test")).isEqualTo(2);
    Key background = queued("test", "background");
    Key hot = queued("test", "hot");
    Key live = submit("test", "live");
    // A live change joins the waiting refresh job and promotes it, rather than adding a render.
    submit("test", "hot");
    List<Key> claimed = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      repository.claim(1, "priority-test", Duration.ofMinutes(1), 3, DEFINITIONS)
          .forEach(claim -> claimed.add(key(claim.job())));
    }
    assertThat(claimed).containsExactly(hot, live, background);
  }

  @Test
  void aRefreshWaitsForAnUncommittedLiveChangeAndLeavesItsJobAlone() throws Exception {
    caseRef("live");
    caseRef("other");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> refresh = tx.execute(status -> {
        submit("test", "live");
        Future<Integer> running = pool.submit(() -> refresh("test"));
        awaitSessionBlockedOnLock();
        return running;
      });
      assertThat(refresh.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(column(queued("test", "live"), "priority")).isEqualTo("0");
    assertThat(column(queued("test", "other"), "priority")).isEqualTo("-1");
  }

  @Test
  void aRefreshWaitsForAnEventThatHoldsTheCaseInsteadOfDeadlockingWithIt() throws Exception {
    long reference = caseRef("live");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> refresh = tx.execute(status -> {
        // An event locks its case, the refresh reaches the case, then the event's handler queues.
        jdbc.query("select 1 from ccd.case_data where reference = :reference for update",
            Map.of("reference", reference), rs -> null);
        Future<Integer> running = pool.submit(() -> refresh("test"));
        awaitSessionBlockedOnLock();
        submit("test", "live");
        return running;
      });
      assertThat(refresh.get(10, TimeUnit.SECONDS)).isZero();
    } finally {
      pool.shutdownNow();
    }
    assertThat(column(queued("test", "live"), "priority")).isEqualTo("0");
  }

  @Test
  void aRefreshAndAChangeToTwoBundlesOfACaseOutsideAnEventDoNotDeadlock() throws Exception {
    caseRef("live");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> refresh = tx.execute(status -> {
        submit("other", "live");
        // One refresh transaction queues every definition, as ManagedCaseBundles.refresh does.
        Future<Integer> running = pool.submit(() -> tx.execute(inRefresh ->
            refresh("test") + refresh("other")));
        awaitSessionBlockedOnLock();
        submit("test", "live");
        return running;
      });
      assertThat(refresh.get(10, TimeUnit.SECONDS)).isZero();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aBundleFinishingAsItsCaseIsDeletedWaitsForTheDeletionAndDiscardsItsPdf() throws Exception {
    Key job = key(submit(simpleRequest(UUID.randomUUID())));
    FakeStore handler = storing((selection, artifact) -> STORED);
    BundleJobWorker worker = worker(new FakeRenderer(tempDir), asSubmitted(),
        handler, quickRetries(3), Runnable::run, 5, 5, Duration.ofMinutes(5));
    Map<String, Long> reference = Map.of("reference", job.caseReference());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> finishing = tx.execute(status -> {
        // The deletion holds the case, the bundle reaches its completion, then the case goes.
        jdbc.query("select 1 from ccd.case_data where reference = :reference for update", reference,
            rs -> null);
        Future<?> running = pool.submit(worker::poll);
        awaitSessionBlockedOnLock();
        jdbc.update("delete from ccd.case_data where reference = :reference", reference);
        return running;
      });
      finishing.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(repository.find(job.definition(), job.caseReference())).isEmpty();
    assertThat(handler.deleted).containsExactly(STORED.id());
  }

  @Test
  void contendingWorkersSkipEachOthersLockedRowsAndNeverDoubleClaim() throws Exception {
    List<Key> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      ids.add(key(submit(simpleRequest(UUID.randomUUID()))));
    }
    CountDownLatch firstClaimHeld = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // Worker A claims three rows and holds its transaction (and so its row locks) open.
      Future<List<ClaimedBundleJob>> first = pool.submit(() -> tx.execute(status -> {
        List<ClaimedBundleJob> claimed = repository.claim(3, "worker-a", Duration.ofMinutes(5), 3, DEFINITIONS);
        firstClaimHeld.countDown();
        awaitQuietly(release);
        return claimed;
      }));
      assertThat(firstClaimHeld.await(10, TimeUnit.SECONDS)).isTrue();
      // B returning at all proves SKIP LOCKED did not block; A releases only after B is done.
      List<ClaimedBundleJob> second = repository.claim(6, "worker-b", Duration.ofMinutes(5), 3, DEFINITIONS);
      release.countDown();
      List<ClaimedBundleJob> firstClaimed = first.get(10, TimeUnit.SECONDS);

      assertThat(firstClaimed).hasSize(3);
      assertThat(second).hasSize(3);
      Set<Key> claimed = new HashSet<>();
      firstClaimed.forEach(job -> claimed.add(key(job.job())));
      second.forEach(job -> claimed.add(key(job.job())));
      assertThat(claimed).containsExactlyInAnyOrderElementsOf(ids);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void expiredLeaseIsReclaimedAndTheStaleWorkersLateWriteIsIgnored() throws Exception {
    submit(simpleRequest(UUID.randomUUID()));
    List<ClaimedBundleJob> first = repository.claim(1, "worker-a", Duration.ofMillis(100), 3, DEFINITIONS);
    assertThat(first).hasSize(1);
    assertThat(first.get(0).job().attempts()).isEqualTo(1);
    assertThat(repository.claim(1, "worker-b", Duration.ofMinutes(5), 3, DEFINITIONS)).isEmpty();

    Thread.sleep(250);
    List<ClaimedBundleJob> reclaimed = repository.claim(1, "worker-b", Duration.ofMinutes(5), 3, DEFINITIONS);
    assertThat(reclaimed).hasSize(1);
    assertThat(reclaimed.get(0).job().attempts()).isEqualTo(2);
    Key id = key(reclaimed.get(0).job());
    assertThat(column(id, "lease_owner")).isEqualTo("worker-b");

    // The stale worker's completion, failure and requeue are all rejected; B's outcome stands.
    ClaimedBundleJob stale = first.get(0);
    assertThat(repository.markCompleted(stale, bundleRecord("a"), null)).isFalse();
    assertThat(bundle(id)).isEmpty();
    assertThat(repository.markFailed(stale, BundleErrorCode.ASSEMBLY_FAILED, "late", "[]")).isFalse();
    assertThat(repository.requeueForRetry(stale, java.time.Instant.now())).isFalse();
    assertThat(job(id).state()).isEqualTo(BundleJobState.IN_PROGRESS);
    assertThat(repository.markCompleted(reclaimed.get(0), bundleRecord("b"), null)).isTrue();
    // Only the lease holder's bundle is recorded.
    assertThat(bundle(id).orElseThrow().sha256()).isEqualTo("sha-b");
    assertThat(jdbc.queryForObject("select count(*) from bundling.bundle", Map.of(), Integer.class))
        .isEqualTo(1);
  }

  @Test
  void aJobReclaimedByTheSameWorkerFencesOutItsEarlierAttempt() throws Exception {
    UUID id = UUID.randomUUID();
    submit(simpleRequest(id));
    Map<Integer, CountDownLatch> started = Map.of(1, new CountDownLatch(1), 2, new CountDownLatch(1));
    Map<Integer, CountDownLatch> release = Map.of(1, new CountDownLatch(1), 2, new CountDownLatch(1));
    Map<Integer, StoredBundleDocument> stored = Map.of(1, stored(), 2, stored());
    java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
    FakeStore handler = storing((selection, artifact) -> {
      int attempt = attempts.incrementAndGet();
      started.get(attempt).countDown();
      awaitQuietly(release.get(attempt));
      return stored.get(attempt);
    });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      BundleJobWorker worker = worker(new FakeRenderer(tempDir),
          asSubmitted(), handler, quickRetries(3), pool, 5, 2,
          Duration.ofMinutes(5));
      worker.poll();
      assertThat(started.get(1).await(10, TimeUnit.SECONDS)).isTrue();
      jdbc.update("update bundling.bundle_job set lease_expires_at = now() - interval '1 second' "
          + "where case_reference = :reference", Map.of("reference", key(id).caseReference()));
      worker.poll();
      assertThat(started.get(2).await(10, TimeUnit.SECONDS)).isTrue();
      // The earlier attempt finishes first, but the same worker's later claim holds the lease.
      release.get(1).countDown();
      await().atMost(Duration.ofSeconds(10)).until(() -> handler.deleted.size() == 1);
      assertThat(job(id).state()).isEqualTo(BundleJobState.IN_PROGRESS);
      release.get(2).countDown();
      await().atMost(Duration.ofSeconds(10)).until(() -> job(id).state().terminal());
    } finally {
      pool.shutdownNow();
    }
    assertThat(handler.deleted).containsExactly(stored.get(1).id());
    assertThat(bundle(id).orElseThrow().stored()).isEqualTo(stored.get(2));
  }

  @Test
  void transientFailureRequeuesWithBackoffThenBoundedRetryFails()
      throws Exception {
    UUID id = submit(simpleRequest(UUID.randomUUID()));
    BundleGenerationException transientFailure = new BundleGenerationException(
        BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleStage.RESOLVE,
        "The source store returned 503.", "Check dm-store health.",
        List.of(new DocumentFailure("doc-1", new DocumentReference("case-documents", "d1"),
            BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, "dm-store 503")));
    FakeRenderer renderer = new FakeRenderer(tempDir)
        .onNextRender(FakeRenderer.failure(transientFailure))
        .onNextRender(FakeRenderer.failure(transientFailure));
    BundleJobWorker worker = directWorker(renderer, quickRetries(2));

    worker.poll();
    BundleJob requeued = job(id);
    assertThat(requeued.state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(requeued.attempts()).isEqualTo(1);
    assertThat(requeued.failure()).isEmpty();
    assertThat(column(id, "next_attempt_at")).isNotNull();

    Thread.sleep(50);
    worker.poll();
    BundleJob failed = job(id);
    assertThat(failed.state()).isEqualTo(BundleJobState.FAILED);
    assertThat(failed.attempts()).isEqualTo(2);
    BundleJobFailure failure = failed.failure().orElseThrow();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    assertThat(failure.message()).contains("Retries exhausted after 2 attempt(s)");
    assertThat(failure.documentFailures()).extracting(DocumentFailure::documentId)
        .containsExactly("doc-1");
    // Exhausted means exhausted: nothing is claimable any more.
    worker.poll();
    assertThat(renderer.renders).hasSize(2);
  }

  @Test
  void nonTransientAndCompletionFailuresAreTerminalOnTheFirstAttempt() throws Exception {
    UUID notFound = submit(simpleRequest(UUID.randomUUID()));
    FakeRenderer renderer = new FakeRenderer(tempDir)
        .onNextRender(FakeRenderer.failure(new BundleGenerationException(
            BundleErrorCode.DOCUMENT_NOT_FOUND, BundleStage.RESOLVE,
            "Two source documents do not exist.", "Correct the references and resubmit.",
            List.of(new DocumentFailure("doc-1", new DocumentReference("case-documents", "d1"),
                    BundleErrorCode.DOCUMENT_NOT_FOUND, "no such document"),
                new DocumentFailure("doc-2", new DocumentReference("case-documents", "d2"),
                    BundleErrorCode.DOCUMENT_NOT_FOUND, "no such document")))));
    BundleJobWorker worker = directWorker(renderer);
    worker.poll();

    BundleJobFailure failure = job(notFound).failure().orElseThrow();
    assertThat(job(notFound).state()).isEqualTo(BundleJobState.FAILED);
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_NOT_FOUND);
    assertThat(failure.message()).contains("doc-1").contains("doc-2");
    assertThat(failure.documentFailures()).extracting(DocumentFailure::documentId)
        .containsExactly("doc-1", "doc-2");
    assertThat(column(notFound, "next_attempt_at")).isNull();
    worker.poll();
    assertThat(renderer.renders).hasSize(1);

    // A retryable code whose cause is a permanent Docmosis answer (a missing template) is
    // terminal too: retrying cannot fix it.
    UUID missingTemplate = submit(simpleRequest(UUID.randomUUID()));
    FakeRenderer coverFails = new FakeRenderer(tempDir).onNextRender(FakeRenderer.failure(
        new BundleGenerationException(BundleErrorCode.COVER_PAGE_FAILED, BundleStage.CONVERT,
            "The cover page template could not be rendered.", "Check the template exists.",
            List.of(), new uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException(
                "Docmosis returned HTTP 404", false))));
    directWorker(coverFails).poll();
    assertThat(job(missingTemplate).state()).isEqualTo(BundleJobState.FAILED);
    assertThat(column(missingTemplate, "next_attempt_at")).isNull();

    // A store that rejects the PDF fails the rendered job with COMPLETION_FAILED; the raw
    // exception message stays out of the row, and the result is still closed.
    UUID unstored = submit(simpleRequest(UUID.randomUUID()));
    worker(new FakeRenderer(tempDir), asSubmitted(),
        storing((selection, artifact) -> {
          throw new IllegalArgumentException("signed-url=https://secret");
        }), quickRetries(3), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();
    BundleJobFailure completion = job(unstored).failure().orElseThrow();
    assertThat(completion.code()).isEqualTo(BundleErrorCode.COMPLETION_FAILED);
    assertThat(completion.message()).contains("IllegalArgumentException").doesNotContain("secret");
    assertThat(Files.list(tempDir)).isEmpty();
  }

  @Test
  void aSelectorThatThrowsFailsTheJobWithoutReachingTheRenderer() {
    Key submitted = submit("bundle", "case-1");
    FakeRenderer renderer = new FakeRenderer(tempDir);
    List<Long> selected = new CopyOnWriteArrayList<>();

    worker(renderer, reference -> {
      selected.add(reference);
      throw new IllegalArgumentException("No such case");
    }, storingAnywhere(), quickRetries(3), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();

    assertThat(selected).containsExactly(caseRef("case-1"));
    assertThat(job(submitted).failure().orElseThrow().code())
        .isEqualTo(BundleErrorCode.JOB_REQUEST_UNREADABLE);
    assertThat(renderer.renders).isEmpty();
  }

  @Test
  void noBundleIsRecordedForABundleWhosePdfCouldNotBeStored() {
    Key submitted = submit("bundle", "case-1");
    FakeStore failing = storing((selection, artifact) -> {
      throw new IllegalArgumentException("the store rejects the document");
    });

    worker(new FakeRenderer(tempDir), twoDocumentSelector(), failing,
        quickRetries(3), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();

    assertThat(job(submitted).state()).isEqualTo(BundleJobState.FAILED);
    assertThat(repository.findLatestBundle("bundle", caseRef("case-1"))).isEmpty();
  }

  @Test
  void aWorkerLeavesJobsForDefinitionsItDoesNotHaveToAWorkerThatDoes() throws Exception {
    Key added = submit("added-in-the-next-release", "case-1");

    assertThat(repository.claim(5, "old-pod", Duration.ofMinutes(5), 3, List.of("bundle"))).isEmpty();
    assertThat(repository.claim(5, "old-pod", Duration.ofMinutes(5), 3, List.of())).isEmpty();
    // Nor does it give up on one whose lease expired, which the pods that have it can still retry.
    assertThat(repository.claim(5, "new-pod", Duration.ofMillis(1), 1,
        List.of("bundle", "added-in-the-next-release")))
        .extracting(claimed -> key(claimed.job())).containsExactly(added);
    Thread.sleep(20);
    assertThat(repository.failExhaustedStaleJobs(1, List.of("bundle"))).isEmpty();
    assertThat(repository.failExhaustedStaleJobs(1, List.of("bundle", "added-in-the-next-release")))
        .containsExactly("added-in-the-next-release of case " + added.caseReference());
  }

  @Test
  void aDatabaseErrorWhileSelectingIsTriedAgainRatherThanFailingTheBundle() {
    UUID id = submit(simpleRequest(UUID.randomUUID()));
    java.util.concurrent.atomic.AtomicBoolean lost = new java.util.concurrent.atomic.AtomicBoolean();
    LongFunction<BundleRequest> selector = reference -> {
      if (lost.compareAndSet(false, true)) {
        throw new org.springframework.dao.QueryTimeoutException("connection lost");
      }
      return REQUESTS.get(reference);
    };
    BundleJobWorker worker = worker(new FakeRenderer(tempDir), selector,
        storingAnywhere(), quickRetries(3), Runnable::run, 5, 5, Duration.ofMillis(50));

    worker.poll();

    assertThat(job(id).state()).isNotEqualTo(BundleJobState.FAILED);
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      worker.poll();
      assertThat(job(id).state()).isEqualTo(BundleJobState.COMPLETED);
    });
  }

  @Test
  void aBundleMissingTemporarilyUnavailableDocumentsIsReRunLaterUntilTheBound() {
    Key id = submit("bundle", "case-1");
    FakeRenderer renderer = new FakeRenderer(tempDir);
    for (int i = 0; i < 4; i++) {
      renderer.onNextRender(request -> renderer(tempDir).success(request, List.of(), List.of(),
          List.of("doc-gone")));
    }
    BundleJobWorker worker = worker(renderer, twoDocumentSelector(), storingAnywhere(),
        new BundleJobRetryPolicy(3, Duration.ofMillis(1), 2.0, Duration.ofMillis(2), Duration.ofMinutes(15), 2),
        Runnable::run, 5, 5, Duration.ofMinutes(5));

    worker.poll();

    assertThat(job(id).state().terminal()).as("a delayed re-run is not shown as regenerating the bundle")
        .isTrue();
    assertThat(column(id, "unavailable_requeues")).isEqualTo("1");
    assertThat(jdbc.queryForObject("select next_attempt_at > now() + interval '14 minutes' "
        + "from bundling.bundle_job where case_reference = :reference",
        Map.of("reference", id.caseReference()), Boolean.class)).isTrue();
    worker.poll();
    assertThat(renderer.renders).as("the re-run waits for its delay").hasSize(1);

    // A change to the case wants a bundle now, and starts the count of re-runs again.
    submit("bundle", "case-1");
    assertThat(job(id).state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(column(id, "next_attempt_at")).isNull();
    worker.poll();
    assertThat(renderer.renders).hasSize(2);
    assertThat(column(id, "unavailable_requeues")).isEqualTo("1");

    jdbc.update("update bundling.bundle_job set next_attempt_at = now()", Map.of());
    worker.poll();
    assertThat(renderer.renders).hasSize(3);
    assertThat(column(id, "unavailable_requeues")).isEqualTo("2");

    jdbc.update("update bundling.bundle_job set next_attempt_at = now()", Map.of());
    worker.poll();
    assertThat(renderer.renders).hasSize(4);
    assertThat(column(id, "next_attempt_at")).as("the bound of two re-runs is reached").isNull();
    assertThat(job(id).state().terminal()).isTrue();
  }

  @Test
  void aRenderMayRetryWhileAttemptsRemainAndIsFinalOnTheLastAttempt() {
    UUID id = UUID.randomUUID();
    submit(simpleRequest(id));
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(FakeRenderer.failure(
        new BundleGenerationException(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleStage.RESOLVE,
            "CDAM timed out", "Retry", List.of())));
    BundleJobWorker worker = directWorker(renderer, quickRetries(2));

    worker.poll();
    await().atMost(Duration.ofSeconds(5)).until(() -> {
      worker.poll();
      return job(id).state().terminal();
    });

    assertThat(renderer.attempts).containsExactly(RenderAttempt.RETRYABLE, RenderAttempt.FINAL);
    assertThat(job(id).state()).isEqualTo(BundleJobState.COMPLETED);
  }

  private Key queued(String definition, String caseKey) {
    Key key = new Key(definition, caseRef(caseKey));
    assertThat(job(key).state()).isEqualTo(BundleJobState.QUEUED);
    return key;
  }

  private static LongFunction<BundleRequest> twoDocumentSelector() {
    return reference -> BundleRequest.builder()
        .title("Selected at execution").fileName("selected.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-kept"))
            .document(document("doc-gone")).build())
        .build();
  }

  @Test
  void aChangeWhileABundleRendersQueuesItAgainSoACaseHasOneRenderAtATime() throws Exception {
    Key id = submit("hearing", "case-1");
    // Changes to a bundle share its one row.
    submit("hearing", "case-1");
    assertThat(jdbc.queryForObject("select count(*) from bundling.bundle_job", Map.of(), Integer.class))
        .isEqualTo(1);

    CountDownLatch renderStarted = new CountDownLatch(1);
    CountDownLatch releaseRender = new CountDownLatch(1);
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(request -> {
      renderStarted.countDown();
      awaitQuietly(releaseRender);
      return renderer(tempDir).success(request, List.of());
    });
    LongFunction<BundleRequest> selector = reference -> BundleRequest.builder()
        .title("Bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      BundleJobWorker worker = worker(renderer, selector, storingAnywhere(),
          quickRetries(3), pool, 5, 5, Duration.ofMinutes(5));
      worker.poll();
      assertThat(renderStarted.await(10, TimeUnit.SECONDS)).isTrue();

      // A change while it renders queues the bundle again, but not for a second render yet.
      submit("hearing", "case-1");
      assertThat(job(id).state()).isEqualTo(BundleJobState.QUEUED);
      worker.poll();
      assertThat(renderer.renders).hasSize(1);

      // The render publishes its bundle and leaves the row queued, so it is rendered again.
      releaseRender.countDown();
      await().atMost(Duration.ofSeconds(10)).until(() -> column(id, "lease_owner") == null);
      assertThat(job(id).state()).isEqualTo(BundleJobState.QUEUED);
      assertThat(repository.findLatestBundle("hearing", caseRef("case-1"))).isPresent();

      worker.poll();
      await().atMost(Duration.ofSeconds(10)).until(() -> job(id).state().terminal());
    } finally {
      pool.shutdownNow();
    }
    assertThat(renderer.renders).hasSize(2);
    assertThat(jdbc.queryForObject("select count(*) from bundling.bundle", Map.of(), Integer.class))
        .isEqualTo(2);
  }

  @Test
  void aBundleThatFailsWhileAChangeArrivesIsRenderedAgainRatherThanLeftFailed() {
    Key id = submit("hearing", "case-1");
    ClaimedBundleJob claimed = repository.claim(1, "worker-a", Duration.ofMinutes(5), 3, DEFINITIONS).get(0);
    submit("hearing", "case-1");

    assertThat(repository.markFailed(claimed, BundleErrorCode.ASSEMBLY_FAILED, "failed", "[]"))
        .isTrue();

    assertThat(job(id).state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(job(id).failure()).isEmpty();
    assertThat(repository.claim(1, "worker-b", Duration.ofMinutes(5), 3, DEFINITIONS)).hasSize(1);
  }

  @Test
  void aCoalescedSubmissionWaitsForAConcurrentUncommittedOneAndJoinsIt() throws Exception {
    CountDownLatch firstSubmitted = new CountDownLatch(1);
    CountDownLatch commitFirst = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Key> first = pool.submit(() -> tx.execute(status -> {
        Key id = submit("hearing", "case-9");
        firstSubmitted.countDown();
        awaitQuietly(commitFirst);
        return id;
      }));
      assertThat(firstSubmitted.await(10, TimeUnit.SECONDS)).isTrue();
      // Cannot see the uncommitted row, so it inserts, blocks on the unique index, then joins.
      Future<Key> second = pool.submit(() -> tx.execute(status ->
          submit("hearing", "case-9")));
      awaitSessionBlockedOnLock();
      assertThat(second.isDone()).isFalse();
      commitFirst.countDown();

      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(first.get(10, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    assertThat(jdbc.queryForObject("select count(*) from bundling.bundle_job", Map.of(),
        Integer.class)).isEqualTo(1);
  }

  @Test
  void aJoinedJobIsNotClaimedUntilTheJoiningTransactionCommits() throws Exception {
    Key waiting = submit("hearing", "case-5");
    CountDownLatch joined = new CountDownLatch(1);
    CountDownLatch commitJoin = new CountDownLatch(1);
    FakeRenderer renderer = new FakeRenderer(tempDir);
    LongFunction<BundleRequest> selector = reference -> BundleRequest.builder()
        .title("Bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build();
    BundleJobWorker worker = worker(renderer, selector, storingAnywhere(),
        quickRetries(3), Runnable::run, 5, 5, Duration.ofMinutes(5));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // A change to the waiting bundle has not committed yet.
      Future<Key> joining = pool.submit(() -> tx.execute(status -> {
        Key id = submit("hearing", "case-5");
        joined.countDown();
        awaitQuietly(commitJoin);
        return id;
      }));
      assertThat(joined.await(10, TimeUnit.SECONDS)).isTrue();

      // Its selector would not see the change yet, so the worker must leave the bundle alone.
      worker.poll();
      assertThat(renderer.renders).isEmpty();
      assertThat(job(waiting).state()).isEqualTo(BundleJobState.QUEUED);

      commitJoin.countDown();
      assertThat(joining.get(10, TimeUnit.SECONDS)).isEqualTo(waiting);
    } finally {
      pool.shutdownNow();
    }
    worker.poll();
    assertThat(job(waiting).state()).isEqualTo(BundleJobState.COMPLETED);
  }

  @Test
  void aChangeWhileABundleAwaitsARetryMakesTheRetryImmediate() {
    Key retrying = submit("hearing", "case-6");
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(FakeRenderer.failure(
        new BundleGenerationException(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
            BundleStage.RESOLVE, "transient", "retry", List.of())));
    LongFunction<BundleRequest> selector = reference -> BundleRequest.builder()
        .title("Bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build();
    BundleJobWorker worker = worker(renderer, selector, storingAnywhere(),
        new BundleJobRetryPolicy(3, Duration.ofHours(1), 2.0, Duration.ofHours(1)), Runnable::run, 5, 5, Duration.ofMinutes(5));
    worker.poll();
    assertThat(job(retrying).state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(job(retrying).attempts()).isEqualTo(1);
    worker.poll();
    assertThat(renderer.renders).as("the retry waits for its delay").hasSize(1);

    submit("hearing", "case-6");

    worker.poll();
    assertThat(job(retrying).state()).isEqualTo(BundleJobState.COMPLETED);
  }

  @Test
  void upgradingClearsJobsAndBundlesThatWereNotKeyedByCase() {
    jdbc.getJdbcTemplate().execute("create database upgrade_db");
    DataSource upgradeSource = dataSourceFor("upgrade_db");
    // Start from the previously released schema, holding a job and its bundle.
    Flyway.configure().dataSource(upgradeSource).defaultSchema("bundling").schemas("bundling")
        .locations("classpath:bundling-db/migration").target("0004").load().migrate();
    var upgradeJdbc = new NamedParameterJdbcTemplate(upgradeSource);
    Map<String, Object> row = Map.of("id", UUID.randomUUID(), "key", "managed:case-file:a");
    upgradeJdbc.update("""
        insert into bundling.bundle_job (external_id, request_version, coalesce_key, execution_context)
        values (:id, 3, :key, '{}'::jsonb)
        """, row);
    upgradeJdbc.update("""
        insert into bundling.bundle (job_id, bundle_key, case_reference, selected_at, outcome,
          file_name, media_type, size_bytes, sha256, page_count, request, documents,
          missing_documents, warnings)
        values (:id, :key, '1234567890123456', now(), 'COMPLETED', 'b.pdf', 'application/pdf', 1,
          'sha', 1, '{}', '[]', '[]', '[]')
        """, row);

    sdkMigrationRunner(upgradeSource).run(context -> assertThat(context).hasNotFailed());

    assertThat(upgradeJdbc.queryForObject("""
        select (select count(*) from bundling.bundle_job) + (select count(*) from bundling.bundle)
        """, Map.of(), Integer.class)).isZero();
  }

  @Test
  void migrationLandsInTheBundlingSchemaAndLeavesTheConsumersFlywayHistoryAlone() {
    JdbcTemplate admin = new JdbcTemplate(dataSource);
    admin.execute("create database consumer_db");
    DataSource consumerDataSource = dataSourceFor("consumer_db");
    JdbcTemplate consumer = new JdbcTemplate(consumerDataSource);
    // A consumer that has been running its own Flyway migrations before adopting the module.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
        .withPropertyValues(CONSUMER_MIGRATIONS)
        .withBean(DataSource.class, () -> consumerDataSource)
        .run(context -> assertThat(context).hasNotFailed());
    List<Map<String, Object>> consumerHistoryBefore = history(consumer, "public");
    assertThat(consumerHistoryBefore).hasSize(1);

    sdkMigrationRunner(consumerDataSource).run(context -> assertThat(context).hasNotFailed());

    // The outbox lives in its own schema with its own history; the runtime's ccd schema came
    // with it, and the application's history and tables in public are exactly as they were.
    assertThat(tables(consumer, "bundling")).containsExactly("bundle", "bundle_job", "flyway_schema_history", "refresh_key");
    assertThat(history(consumer, "bundling")).extracting(row -> row.get("version"))
        .contains("0001", "0002");
    assertThat(consumer.queryForList("select schema_name from information_schema.schemata "
        + "where schema_name in ('ccd', 'bundling') order by schema_name", String.class))
        .containsExactly("bundling", "ccd");
    assertThat(tables(consumer, "public")).containsExactly("consumer_app_table",
        "flyway_schema_history");
    assertThat(history(consumer, "public")).isEqualTo(consumerHistoryBefore);
  }

  private static ApplicationContextRunner sdkMigrationRunner(DataSource dataSource) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DecentralisedFlywayAutoConfiguration.class,
            BundleJobFlywayAutoConfiguration.class, FlywayAutoConfiguration.class))
        .withPropertyValues(CONSUMER_MIGRATIONS)
        .withBean(DataSource.class, () -> dataSource);
  }

  private static List<String> tables(JdbcTemplate db, String schema) {
    return db.queryForList("select table_name from information_schema.tables "
        + "where table_schema = ? order by table_name", String.class, schema);
  }

  private static List<Map<String, Object>> history(JdbcTemplate db, String schema) {
    return db.queryForList("select installed_rank, version, description, checksum from "
        + schema + ".flyway_schema_history order by installed_rank");
  }

  private static DataSource dataSourceFor(String database) {
    return new DriverManagerDataSource(
        "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/"
            + database, POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static BundleJobRetryPolicy quickRetries(int maxAttempts) {
    return new BundleJobRetryPolicy(maxAttempts, Duration.ofMillis(1), 2.0, Duration.ofMillis(2));
  }

  private static BundleJobRepository.BundleRecord bundleRecord(String name) {
    BundleRequest request = simpleRequest(UUID.randomUUID());
    return new BundleJobRepository.BundleRecord("COMPLETED", name + ".pdf", "application/pdf", 10,
        "sha-" + name, 1, new BundleJobJson().write(request), "[]", "[]", "[]", stored());
  }

  /** Queues the bundle of a case of its own whose selection is the request, and returns its id. */
  private static UUID submit(BundleRequest request) {
    long caseReference = caseRef(request.externalId().toString());
    REQUESTS.put(caseReference, request);
    repository.changed("test", caseReference);
    return request.externalId();
  }

  /** Queues the case's bundle, as a change to its documents does. */
  private static Key submit(String definition, String caseKey) {
    Key key = new Key(definition, caseRef(caseKey));
    repository.changed(definition, key.caseReference());
    return key;
  }

  /** A bundle's queue row: its definition and case. */
  private record Key(String definition, long caseReference) {
  }

  private static Key key(BundleJob job) {
    return new Key(job.definition(), job.caseReference());
  }

  /** The bundle queued by submitting a request under the id. */
  private static Key key(UUID id) {
    return new Key("test", caseRef(id.toString()));
  }

  private static Optional<BundleMetadata> bundle(Key key) {
    return repository.findLatestBundle(key.definition(), key.caseReference());
  }

  private static Optional<BundleMetadata> bundle(UUID id) {
    return bundle(key(id));
  }

  /** The CCD case the name stands for, created on first use. */
  private static long caseRef(String name) {
    return CASES.computeIfAbsent(name, unused -> {
      long reference = 1000000000000000L + CASES.size();
      insertCase(reference);
      return reference;
    });
  }

  /** Commits the case on a connection of its own, whatever transaction the test is in. */
  private static void insertCase(long reference) {
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.execute(INSERT_CASE.replace(":reference", Long.toString(reference)));
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int refresh(String definition) {
    return repository.refresh(definition, "CaseType");
  }

  private static LongFunction<BundleRequest> asSubmitted() {
    return REQUESTS::get;
  }

  private static StoredBundleDocument stored() {
    UUID id = UUID.randomUUID();
    return new StoredBundleDocument(id, "http://dm/" + id, "http://dm/" + id + "/binary");
  }

  private static FakeStore storing(Storing storing) {
    return new FakeStore(storing);
  }

  private static FakeStore storingAnywhere() {
    return storing((selection, artifact) -> stored());
  }

  /** A worker whose every definition selects with the function and stores in the store. */
  private static BundleJobWorker worker(BundleRenderer renderer, LongFunction<BundleRequest> selecting,
      BundleDocumentStore store, BundleJobRetryPolicy policy, Executor executor, int batchSize,
      int maxConcurrentRenders, Duration leaseDuration) {
    List<CaseBundleDefinition> definitions = DEFINITIONS.stream()
        .<CaseBundleDefinition>map(name -> new CaseBundleDefinition() {
          @Override
          public String name() {
            return name;
          }

          @Override
          public String caseType() {
            return "CaseType";
          }

          @Override
          public BundleRequest select(long caseReference) {
            return selecting.apply(caseReference);
          }
        }).toList();
    return new BundleJobWorker(repository, renderer,
        new ManagedCaseBundles(repository, store, transactions, definitions, null), policy, executor,
        batchSize, maxConcurrentRenders, leaseDuration);
  }

  private static BundleJobWorker directWorker(FakeRenderer renderer, BundleJobRetryPolicy policy) {
    return worker(renderer, asSubmitted(), storingAnywhere(), policy, Runnable::run, 5, 5,
        Duration.ofMinutes(5));
  }

  private static BundleJobWorker directWorker(FakeRenderer renderer) {
    return directWorker(renderer, quickRetries(3));
  }

  // Waits until some other session is blocked waiting for a row or index lock.
  private static void awaitSessionBlockedOnLock() {
    await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
        "select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Map.of(),
        Integer.class) > 0);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static BundleJob job(Key key) {
    return repository.find(key.definition(), key.caseReference()).orElseThrow();
  }

  private static BundleJob job(UUID id) {
    return job(key(id));
  }

  private static String column(Key key, String column) {
    return jdbc.queryForObject("select " + column + "::text from bundling.bundle_job "
        + "where definition = :definition and case_reference = :reference",
        Map.of("definition", key.definition(), "reference", key.caseReference()), String.class);
  }

  private static String column(UUID id, String column) {
    return column(key(id), column);
  }

  private static BundleDocument document(String id) {
    return BundleDocument.builder().id(id).title("Document " + id)
        .reference(new DocumentReference("case-documents", id)).build();
  }

  private static BundleRequest simpleRequest(UUID id) {
    return BundleRequest.builder().externalId(id).title("Hearing bundle")
        .fileName("hearing-bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build();
  }

  // Nested sections, a date and confidentiality: the whole persisted shape.
  private static FakeRenderer renderer(Path base) {
    return new FakeRenderer(base);
  }

  // Replaces only the rendering pipeline: a queue of per-render behaviours, defaulting to a
  // success that writes a small file into a fresh job directory under the base and wraps it.
  private static final class FakeRenderer implements BundleRenderer {
    private final Path base;
    private final Queue<Function<BundleRequest, BundleResult>> behaviours =
        new ConcurrentLinkedQueue<>();
    final List<BundleRequest> renders = new CopyOnWriteArrayList<>();
    final List<RenderAttempt> attempts = new CopyOnWriteArrayList<>();

    FakeRenderer(Path base) {
      this.base = base;
    }

    FakeRenderer onNextRender(Function<BundleRequest, BundleResult> behaviour) {
      behaviours.add(behaviour);
      return this;
    }

    static Function<BundleRequest, BundleResult> failure(BundleGenerationException failure) {
      return request -> {
        throw failure;
      };
    }

    BundleResult success(BundleRequest request, List<BundleWarning> warnings) {
      return success(request, warnings, List.of(), List.of());
    }

    BundleResult success(BundleRequest request, List<BundleWarning> warnings, List<String> notFound) {
      return success(request, warnings, notFound, List.of());
    }

    // The named documents come back as placeholders: notFound ones permanently, unavailable ones
    // temporarily.
    BundleResult success(BundleRequest request, List<BundleWarning> warnings, List<String> notFound,
        List<String> unavailable) {
      try {
        Path jobDir = Files.createTempDirectory(base, "job-");
        Path pdf = Files.write(jobDir.resolve(request.fileName()), "%PDF-1.4 fake".getBytes());
        List<DocumentResult> documents = new ArrayList<>();
        List<MissingDocument> missing = new ArrayList<>();
        for (BundleDocument document : request.allDocuments()) {
          int page = documents.size() + missing.size() + 1;
          if (notFound.contains(document.id()) || unavailable.contains(document.id())) {
            boolean temporary = unavailable.contains(document.id());
            missing.add(new MissingDocument(document.id(), document.reference(),
                temporary ? MissingDocumentReason.UNAVAILABLE : MissingDocumentReason.NOT_FOUND,
                temporary ? BundleErrorCode.DOCUMENT_RESOLUTION_FAILED : BundleErrorCode.DOCUMENT_NOT_FOUND,
                "test", page, document.title(), List.of()));
          } else {
            documents.add(new DocumentResult(document.id(), document.reference(), "application/pdf",
                "sha-" + document.id(), 1, page));
          }
        }
        return new BundleResult(new FileArtifact(pdf, documents.size()), warnings, documents, missing, Map.of(),
            () -> {
              try {
                Files.deleteIfExists(pdf);
                Files.deleteIfExists(jobDir);
              } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
            });
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }

    @Override
    public BundleResult render(BundleRequest request, BundleExecutionContext context,
        RenderAttempt attempt) {
      attempts.add(attempt);
      return render(request, context);
    }

    @Override
    public BundleResult render(BundleRequest request, BundleExecutionContext context) {
      renders.add(request);
      Function<BundleRequest, BundleResult> behaviour = behaviours.poll();
      return behaviour == null ? success(request, List.of()) : behaviour.apply(request);
    }

    @Override
    public Set<String> handledMediaTypes() {
      return Set.of("application/pdf");
    }
  }

  private interface Storing {
    StoredBundleDocument store(SelectedCaseBundle selection, BundleArtifact artifact) throws Exception;
  }

  private static final class FakeStore implements BundleDocumentStore {
    final List<UUID> deleted = new CopyOnWriteArrayList<>();
    private final Storing storing;

    FakeStore(Storing storing) {
      this.storing = storing;
    }

    @Override
    public StoredBundleDocument store(SelectedCaseBundle selection, BundleArtifact artifact)
        throws Exception {
      return storing.store(selection, artifact);
    }

    @Override
    public void delete(UUID documentId) {
      deleted.add(documentId);
    }
  }

  private record FileArtifact(Path file, int pageCount) implements BundleArtifact {
    @Override
    public String fileName() {
      return file.getFileName().toString();
    }

    @Override
    public String mediaType() {
      return "application/pdf";
    }

    @Override
    public long size() {
      return file.toFile().length();
    }

    @Override
    public String sha256() {
      return "sha-bundle";
    }

    @Override
    public InputStream open() throws java.io.IOException {
      return new ByteArrayInputStream(Files.readAllBytes(file));
    }
  }
}
