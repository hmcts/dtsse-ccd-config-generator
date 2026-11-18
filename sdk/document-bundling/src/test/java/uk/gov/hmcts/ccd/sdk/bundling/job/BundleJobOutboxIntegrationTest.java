package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.transaction.TransactionDefinition;
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
  private static TransactionTemplate tx;

  @TempDir
  Path tempDir;

  @BeforeAll
  static void migrateThroughTheSdkFlywayStrategy() {
    dataSource = dataSourceFor(POSTGRES.getDatabaseName());
    sdkMigrationRunner(dataSource).run(context -> assertThat(context).hasNotFailed());
    jdbc = new NamedParameterJdbcTemplate(dataSource);
    repository = new BundleJobRepository(jdbc);
    tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
    UUID background = queued("test", "background").externalId();
    UUID hot = queued("test", "hot").externalId();
    UUID live = submit("test", "live").externalId();
    // A live change joins the waiting refresh job and promotes it, rather than adding a render.
    assertThat(submit("test", "hot").externalId()).isEqualTo(hot);
    List<UUID> claimed = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      repository.claim(1, "priority-test", Duration.ofMinutes(1), 3, null)
          .forEach(claim -> claimed.add(claim.job().externalId()));
    }
    assertThat(claimed).containsExactly(hot, live, background);
  }

  @Test
  void aRerunOfARefreshJobStaysBehindLiveChangesAndDoesNotDemoteAWaitingLiveJob() {
    // A refresh job that rendered with unavailable documents queues its re-run at its own priority.
    UUID rerun = repository.insertOrJoin(UUID.randomUUID(), "test", caseRef("refreshed"), null, 1, -1);
    UUID live = submit("test", "live").externalId();
    assertThat(repository.insertOrJoin(UUID.randomUUID(), "test", caseRef("live"), null, 1, -1))
        .isEqualTo(live);
    List<UUID> claimed = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      repository.claim(1, "priority-test", Duration.ofMinutes(1), 3, null).forEach(claim -> {
        claimed.add(claim.job().externalId());
        assertThat(claim.priority()).isEqualTo(claim.job().externalId().equals(live) ? 0 : -1);
      });
    }
    assertThat(claimed).containsExactly(live, rerun);
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
    assertThat(column(queued("test", "live").externalId(), "priority")).isEqualTo("0");
    assertThat(column(queued("test", "other").externalId(), "priority")).isEqualTo("-1");
  }

  @Test
  void aJobForAReferenceThatIsNotACaseIsRejected() {
    assertThatThrownBy(() -> repository.insertOrJoin(UUID.randomUUID(), "test", 42L, null, 0, 0))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void submitThenPollRunsThroughInProgressToCompletedRecordingWhereThePdfWasStored()
      throws Exception {
    UUID id = UUID.randomUUID();
    BundleJob submitted = submit(fullRequest(id));
    assertThat(submitted.state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(submitted.attempts()).isZero();

    CountDownLatch renderStarted = new CountDownLatch(1);
    CountDownLatch releaseRender = new CountDownLatch(1);
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(request -> {
      renderStarted.countDown();
      awaitQuietly(releaseRender);
      return renderer(tempDir).success(request, List.of());
    });
    List<BundleRequest> handled = new CopyOnWriteArrayList<>();
    BundleJobCompletionHandler handler = (job, selection, result) -> {
      handled.add(selection.request());
      assertThat(job.state()).isEqualTo(BundleJobState.IN_PROGRESS);
      try (InputStream pdf = result.artifact().open()) {
        assertThat(pdf.readAllBytes()).isNotEmpty();
      }
      return STORED;
    };
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      BundleJobWorker worker = new BundleJobWorker(repository, renderer,
          asSubmitted(), handler, quickRetries(3), List.of(), pool, 5, 5,
          Duration.ofMinutes(5));
      worker.poll();
      assertThat(renderStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(job(id).state()).isEqualTo(BundleJobState.IN_PROGRESS);
      assertThat(column(id, "lease_owner")).startsWith("bundle-job-worker-");
      releaseRender.countDown();
      await().atMost(Duration.ofSeconds(10)).until(() -> job(id).state().terminal());
    } finally {
      pool.shutdownNow();
    }

    BundleJob completed = job(id);
    assertThat(completed.state()).isEqualTo(BundleJobState.COMPLETED);
    assertThat(completed.attempts()).isEqualTo(1);
    assertThat(completed.failure()).isEmpty();
    assertThat(repository.findBundleForJob(id).orElseThrow().stored()).contains(STORED);
    assertThat(column(id, "lease_owner")).isNull();
    // The request round-tripped through JSON unchanged: the handler saw the whole tree.
    assertThat(handled).hasSize(1);
    BundleRequest seen = handled.get(0);
    assertThat(seen.title()).isEqualTo("Final hearing bundle");
    assertThat(seen.presentation().documentCoverSheets()).isTrue();
    assertThat(seen.presentation().watermark()).contains(
        uk.gov.hmcts.ccd.sdk.bundling.api.WatermarkPreset.allPages("hmcts-logo"));
    assertThat(seen.coverPage()).contains(new uk.gov.hmcts.ccd.sdk.bundling.api.CoverPage(
        "FL-FRM-GOR-ENG-12345.docx", java.util.Map.of("caseReference", "1234")));
    assertThat(seen.allDocuments()).extracting(BundleDocument::id)
        .containsExactly("doc-1", "doc-2", "doc-3");
    assertThat(seen.allDocuments().get(2).media().orElseThrow().duration())
        .contains(Duration.ofMinutes(42));
    assertThat(seen.coverPage().orElseThrow().templateName()).isEqualTo("FL-FRM-GOR-ENG-12345.docx");
    assertThat(seen.presentation().watermark()).isPresent();
    // The worker closed the result: the renderer's job directory is gone.
    assertThat(Files.list(tempDir)).isEmpty();

    // A warning-carrying render lands in COMPLETED_WITH_WARNINGS.
    UUID warned = UUID.randomUUID();
    submit(simpleRequest(warned));
    directWorker(new FakeRenderer(tempDir).onNextRender(request -> renderer(tempDir)
        .success(request, List.of(BundleWarning.of("EMPTY_SECTION_PAGE_INCLUDED", "empty")))))
        .poll();
    assertThat(job(warned).state()).isEqualTo(BundleJobState.COMPLETED_WITH_WARNINGS);
  }

  @Test
  void submitJoinsTheCallersTransaction() {
    UUID rolledBack = UUID.randomUUID();
    tx.execute(status -> {
      submit(simpleRequest(rolledBack));
      assertThat(repository.find(rolledBack)).isPresent();
      status.setRollbackOnly();
      return null;
    });
    assertThat(repository.find(rolledBack)).isEmpty();
  }

  @Test
  void contendingWorkersSkipEachOthersLockedRowsAndNeverDoubleClaim() throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      ids.add(submit(simpleRequest(UUID.randomUUID())).externalId());
    }
    CountDownLatch firstClaimHeld = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // Worker A claims three rows and holds its transaction (and so its row locks) open.
      Future<List<ClaimedBundleJob>> first = pool.submit(() -> tx.execute(status -> {
        List<ClaimedBundleJob> claimed = repository.claim(3, "worker-a", Duration.ofMinutes(5), 3, null);
        firstClaimHeld.countDown();
        awaitQuietly(release);
        return claimed;
      }));
      assertThat(firstClaimHeld.await(10, TimeUnit.SECONDS)).isTrue();
      // B returning at all proves SKIP LOCKED did not block; A releases only after B is done.
      List<ClaimedBundleJob> second = repository.claim(6, "worker-b", Duration.ofMinutes(5), 3, null);
      release.countDown();
      List<ClaimedBundleJob> firstClaimed = first.get(10, TimeUnit.SECONDS);

      assertThat(firstClaimed).hasSize(3);
      assertThat(second).hasSize(3);
      Set<UUID> claimed = new HashSet<>();
      firstClaimed.forEach(job -> claimed.add(job.job().externalId()));
      second.forEach(job -> claimed.add(job.job().externalId()));
      assertThat(claimed).containsExactlyInAnyOrderElementsOf(ids);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void expiredLeaseIsReclaimedAndTheStaleWorkersLateWriteIsIgnored() throws Exception {
    submit(simpleRequest(UUID.randomUUID()));
    List<ClaimedBundleJob> first = repository.claim(1, "worker-a", Duration.ofMillis(100), 3, null);
    assertThat(first).hasSize(1);
    assertThat(first.get(0).job().attempts()).isEqualTo(1);
    assertThat(repository.claim(1, "worker-b", Duration.ofMinutes(5), 3, null)).isEmpty();

    Thread.sleep(250);
    List<ClaimedBundleJob> reclaimed = repository.claim(1, "worker-b", Duration.ofMinutes(5), 3, null);
    assertThat(reclaimed).hasSize(1);
    assertThat(reclaimed.get(0).job().attempts()).isEqualTo(2);
    UUID id = reclaimed.get(0).job().externalId();
    assertThat(column(id, "lease_owner")).isEqualTo("worker-b");

    // The stale worker's completion, failure and requeue are all rejected; B's outcome stands.
    assertThat(repository.markCompleted(id, BundleJobState.COMPLETED, bundleRecord("a"), "worker-a")).isFalse();
    assertThat(repository.findBundleForJob(id)).isEmpty();
    assertThat(repository.markFailed(id, BundleErrorCode.ASSEMBLY_FAILED, "late", "[]", null,
        "worker-a")).isFalse();
    assertThat(repository.requeueForRetry(id, java.time.Instant.now(), "[]", "worker-a"))
        .isFalse();
    assertThat(job(id).state()).isEqualTo(BundleJobState.IN_PROGRESS);
    assertThat(repository.markCompleted(id, BundleJobState.COMPLETED, bundleRecord("b"), "worker-b"))
        .isTrue();
    // Only the lease holder's bundle is recorded.
    assertThat(repository.findBundleForJob(id).orElseThrow().sha256()).isEqualTo("sha-b");
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
    List<StoredBundleDocument> discarded = new CopyOnWriteArrayList<>();
    BundleJobCompletionHandler handler = new BundleJobCompletionHandler() {
      @Override
      public StoredBundleDocument onCompleted(BundleJob job, SelectedCaseBundle selection,
          BundleResult result) {
        started.get(job.attempts()).countDown();
        awaitQuietly(release.get(job.attempts()));
        return stored.get(job.attempts());
      }

      @Override
      public void discarded(BundleJob job, StoredBundleDocument document) {
        discarded.add(document);
      }
    };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      BundleJobWorker worker = new BundleJobWorker(repository, new FakeRenderer(tempDir),
          asSubmitted(), handler, quickRetries(3), List.of(), pool, 5, 2,
          Duration.ofMinutes(5));
      worker.poll();
      assertThat(started.get(1).await(10, TimeUnit.SECONDS)).isTrue();
      jdbc.update("update bundling.bundle_job set lease_expires_at = now() - interval '1 second' "
          + "where external_id = :id", Map.of("id", id));
      worker.poll();
      assertThat(started.get(2).await(10, TimeUnit.SECONDS)).isTrue();
      // The earlier attempt finishes first, but the same worker's later claim holds the lease.
      release.get(1).countDown();
      await().atMost(Duration.ofSeconds(10)).until(() -> discarded.size() == 1);
      assertThat(job(id).state()).isEqualTo(BundleJobState.IN_PROGRESS);
      release.get(2).countDown();
      await().atMost(Duration.ofSeconds(10)).until(() -> job(id).state().terminal());
    } finally {
      pool.shutdownNow();
    }
    assertThat(discarded).containsExactly(stored.get(1));
    assertThat(repository.findBundleForJob(id).orElseThrow().stored()).contains(stored.get(2));
  }

  @Test
  void transientFailureRequeuesWithBackoffThenBoundedRetryFailsCarryingTheHistory()
      throws Exception {
    UUID id = submit(simpleRequest(UUID.randomUUID())).externalId();
    BundleGenerationException transientFailure = new BundleGenerationException(
        BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleStage.RESOLVE,
        "The source store returned 503.", "Check dm-store health.",
        List.of(new DocumentFailure("doc-1", new DocumentReference("case-documents", "d1"),
            BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, "dm-store 503")));
    FakeRenderer renderer = new FakeRenderer(tempDir)
        .onNextRender(FakeRenderer.failure(transientFailure))
        .onNextRender(FakeRenderer.failure(transientFailure));
    List<BundleProgressEvent> events = new CopyOnWriteArrayList<>();
    BundleJobWorker worker = directWorker(renderer, quickRetries(2), List.of(events::add));

    worker.poll();
    BundleJob requeued = job(id);
    assertThat(requeued.state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(requeued.attempts()).isEqualTo(1);
    assertThat(requeued.failure()).isEmpty();
    assertThat(column(id, "next_attempt_at")).isNotNull();
    assertThat(column(id, "transient_history")).contains("DOCUMENT_RESOLUTION_FAILED");

    Thread.sleep(50);
    worker.poll();
    BundleJob failed = job(id);
    assertThat(failed.state()).isEqualTo(BundleJobState.FAILED);
    assertThat(failed.attempts()).isEqualTo(2);
    BundleJobFailure failure = failed.failure().orElseThrow();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    assertThat(failure.message()).contains("Retries exhausted after 2 attempt(s)")
        .contains("attempt 1 at").contains("attempt 2 at");
    assertThat(failure.documentFailures()).extracting(DocumentFailure::documentId)
        .containsExactly("doc-1");
    assertThat(events).extracting(BundleProgressEvent::state).containsExactly(
        BundleJobState.IN_PROGRESS, BundleJobState.QUEUED,
        BundleJobState.IN_PROGRESS, BundleJobState.FAILED);

    // Exhausted means exhausted: nothing is claimable any more.
    worker.poll();
    assertThat(renderer.renders).hasSize(2);
  }

  @Test
  void nonTransientAndCompletionFailuresAreTerminalOnTheFirstAttempt() throws Exception {
    UUID notFound = submit(simpleRequest(UUID.randomUUID())).externalId();
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
    UUID missingTemplate = submit(simpleRequest(UUID.randomUUID())).externalId();
    FakeRenderer coverFails = new FakeRenderer(tempDir).onNextRender(FakeRenderer.failure(
        new BundleGenerationException(BundleErrorCode.COVER_PAGE_FAILED, BundleStage.CONVERT,
            "The cover page template could not be rendered.", "Check the template exists.",
            List.of(), new uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderException(
                "Docmosis returned HTTP 404", false))));
    directWorker(coverFails).poll();
    assertThat(job(missingTemplate).state()).isEqualTo(BundleJobState.FAILED);
    assertThat(column(missingTemplate, "next_attempt_at")).isNull();

    // A throwing completion handler fails the rendered job with COMPLETION_FAILED; the raw
    // exception message stays out of the row, and the result is still closed.
    UUID unstored = submit(simpleRequest(UUID.randomUUID())).externalId();
    new BundleJobWorker(repository, new FakeRenderer(tempDir), asSubmitted(),
        (job, selection, result) -> {
          throw new IllegalStateException("signed-url=https://secret");
        }, quickRetries(3), List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();
    BundleJobFailure completion = job(unstored).failure().orElseThrow();
    assertThat(completion.code()).isEqualTo(BundleErrorCode.COMPLETION_FAILED);
    assertThat(completion.message()).contains("IllegalStateException").doesNotContain("secret");
    assertThat(Files.list(tempDir)).isEmpty();
  }

  @Test
  void aSelectorThatThrowsFailsTheJobWithoutReachingTheRenderer() {
    BundleJob submitted = submit("bundle", "case-1");
    FakeRenderer renderer = new FakeRenderer(tempDir);
    List<BundleJob> selected = new CopyOnWriteArrayList<>();

    new BundleJobWorker(repository, renderer, job -> {
      selected.add(job);
      throw new IllegalArgumentException("Unknown case bundle definition");
    }, summaryHandler(), quickRetries(3), List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();

    assertThat(selected).extracting(BundleJob::externalId, BundleJob::definition, BundleJob::caseReference)
        .containsExactly(tuple(submitted.externalId(), "bundle", caseRef("case-1")));
    assertThat(job(submitted.externalId()).failure().orElseThrow().code())
        .isEqualTo(BundleErrorCode.JOB_REQUEST_UNREADABLE);
    assertThat(renderer.renders).isEmpty();
  }

  @Test
  void aCompletedJobRecordsTheBundleItRenderedFromTheRequestTheSelectorCompiled() {
    BundleJob submitted = submit("bundle", "case-1");
    assertThat(repository.findBundleForJob(submitted.externalId())).isEmpty();
    assertThat(repository.findLatestBundle("bundle", caseRef("case-1"))).isEmpty();
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(request ->
        renderer(tempDir).success(request, List.of(), List.of("doc-gone")));
    List<BundleResult> handed = new CopyOnWriteArrayList<>();
    BundleJobCompletionHandler handler = (job, selection, result) -> {
      // The consumer still receives the full result, to store the PDF where it keeps documents.
      handed.add(result);
      assertThat(repository.findBundleForJob(job.externalId())).isEmpty();
      return null;
    };

    new BundleJobWorker(repository, renderer, twoDocumentSelector(), handler, quickRetries(3),
        List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();

    assertThat(handed).singleElement().satisfies(result -> {
      assertThat(result.pageCount()).isEqualTo(1);
      assertThat(result.missingDocuments()).extracting(MissingDocument::documentId)
          .containsExactly("doc-gone");
    });
    BundleMetadata bundle = repository.findBundleForJob(submitted.externalId()).orElseThrow();
    assertThat(bundle.jobId()).isEqualTo(submitted.externalId());
    assertThat(bundle.definition()).isEqualTo("bundle");
    assertThat(bundle.caseReference()).isEqualTo(caseRef("case-1"));
    assertThat(bundle.selectedAt()).isEqualTo(job(submitted.externalId()).claimedAt().orElseThrow());
    assertThat(bundle.request().title()).isEqualTo("Selected at execution");
    assertThat(bundle.request().allDocuments()).extracting(BundleDocument::id)
        .containsExactly("doc-kept", "doc-gone");
    assertThat(bundle.outcome()).isEqualTo(uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome.COMPLETED_WITH_WARNINGS);
    assertThat(bundle.fileName()).isEqualTo("selected.pdf");
    assertThat(bundle.mediaType()).isEqualTo("application/pdf");
    assertThat(bundle.sha256()).isEqualTo("sha-bundle");
    assertThat(bundle.pageCount()).isEqualTo(1);
    assertThat(bundle.documents()).extracting(DocumentResult::documentId).containsExactly("doc-kept");
    assertThat(bundle.missingDocuments()).singleElement().satisfies(missing -> {
      assertThat(missing.documentId()).isEqualTo("doc-gone");
      assertThat(missing.reason()).isEqualTo(MissingDocumentReason.NOT_FOUND);
      assertThat(missing.startPage()).isEqualTo(2);
    });
    assertThat(bundle.warnings()).isEqualTo(handed.get(0).warnings());
    assertThat(repository.findLatestBundle("bundle", caseRef("case-1")).map(BundleMetadata::id))
        .contains(bundle.id());
    // Only an unavailable document is worth re-running for.
    assertThat(repository.findLatest("bundle", caseRef("case-1")).orElseThrow().externalId())
        .isEqualTo(submitted.externalId());
  }

  @Test
  void noBundleIsRecordedForAJobWhoseCompletionHandlerFailed() {
    BundleJob submitted = submit("bundle", "case-1");
    BundleJobCompletionHandler failing = (job, selection, result) -> {
      throw new IllegalStateException("document store down");
    };

    new BundleJobWorker(repository, new FakeRenderer(tempDir), twoDocumentSelector(), failing,
        quickRetries(3), List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();

    assertThat(job(submitted.externalId()).state()).isEqualTo(BundleJobState.FAILED);
    assertThat(repository.findBundleForJob(submitted.externalId())).isEmpty();
    assertThat(repository.findLatestBundle("bundle", caseRef("case-1"))).isEmpty();
  }

  @Test
  void theLatestBundleForAKeyIsTheOneWhoseDocumentsWereSelectedLastWhateverOrderTheyFinished() {
    UUID older = submit("bundle", "case-1").externalId();
    List<ClaimedBundleJob> olderClaim = repository.claim(1, "worker-a", Duration.ofMinutes(5), 3, null);
    UUID newer = submit("bundle", "case-1").externalId();
    List<ClaimedBundleJob> newerClaim = repository.claim(1, "worker-b", Duration.ofMinutes(5), 3, null);
    assertThat(olderClaim).extracting(claimed -> claimed.job().externalId()).containsExactly(older);
    assertThat(newerClaim).extracting(claimed -> claimed.job().externalId()).containsExactly(newer);

    // The newer selection finishes first; the older one finishing later does not displace it.
    assertThat(repository.markCompleted(newer, BundleJobState.COMPLETED, bundleRecord("newer"),
        "worker-b")).isTrue();
    assertThat(repository.markCompleted(older, BundleJobState.COMPLETED, bundleRecord("older"),
        "worker-a")).isTrue();

    assertThat(repository.findLatestBundle("bundle", caseRef("case-1")).orElseThrow().jobId()).isEqualTo(newer);
    assertThat(repository.findBundleForJob(older).orElseThrow().sha256()).isEqualTo("sha-older");
    assertThat(repository.findLatestBundle("bundle", caseRef("case-2"))).isEmpty();
  }

  @Test
  void theLatestBundleIsTheOneSelectedLastEvenWhenItsJobWasClaimedFirst() {
    final UUID first = submit("bundle", "case-1").externalId();
    final ClaimedBundleJob firstClaim =
        repository.claim(1, "worker-a", Duration.ofMinutes(5), 3, null).get(0);
    final UUID second = submit("bundle", "case-1").externalId();
    final ClaimedBundleJob secondClaim =
        repository.claim(1, "worker-b", Duration.ofMinutes(5), 3, null).get(0);

    // The job claimed second selects and publishes while the first is still waiting to select.
    tx.executeWithoutResult(status -> repository.startSelection(secondClaim.job()));
    assertThat(repository.markCompleted(second, BundleJobState.COMPLETED, bundleRecord("second"),
        "worker-b")).isTrue();
    tx.executeWithoutResult(status -> repository.startSelection(firstClaim.job()));
    assertThat(repository.markCompleted(first, BundleJobState.COMPLETED, bundleRecord("first"),
        "worker-a")).isTrue();

    assertThat(repository.findLatestBundle("bundle", caseRef("case-1")).orElseThrow().jobId())
        .isEqualTo(first);
  }

  @Test
  void aWorkerLeavesJobsForDefinitionsItDoesNotHaveToAWorkerThatDoes() {
    UUID added = submit("added-in-the-next-release", "case-1").externalId();

    assertThat(repository.claim(5, "old-pod", Duration.ofMinutes(5), 3, List.of("bundle"))).isEmpty();
    assertThat(repository.claim(5, "old-pod", Duration.ofMinutes(5), 3, List.of())).isEmpty();
    assertThat(repository.claim(5, "new-pod", Duration.ofMinutes(5), 3,
        List.of("bundle", "added-in-the-next-release")))
        .extracting(claimed -> claimed.job().externalId()).containsExactly(added);
  }

  @Test
  void aBundleMissingTemporarilyUnavailableDocumentsIsReRunLaterUntilTheBound() {
    BundleJob submitted = submit("bundle", "case-1");
    FakeRenderer renderer = new FakeRenderer(tempDir);
    for (int i = 0; i < 3; i++) {
      renderer.onNextRender(request -> renderer(tempDir).success(request, List.of(), List.of(),
          List.of("doc-gone")));
    }
    BundleJobWorker worker = new BundleJobWorker(repository, renderer, twoDocumentSelector(), summaryHandler(),
        new BundleJobRetryPolicy(3, Duration.ofMillis(1), 2.0, Duration.ofMillis(2), Duration.ofMinutes(15), 2),
        List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5));

    worker.poll();

    BundleJob reRun = queued("bundle", "case-1");
    assertThat(reRun.externalId()).isNotEqualTo(submitted.externalId());
    assertThat(repository.findLatest("bundle", caseRef("case-1")).orElseThrow().externalId())
        .as("a delayed re-run is not shown as regenerating the bundle").isEqualTo(submitted.externalId());
    assertThat(column(reRun.externalId(), "unavailable_requeues")).isEqualTo("1");
    assertThat(jdbc.queryForObject("select next_attempt_at > now() + interval '14 minutes' "
        + "from bundling.bundle_job where external_id = :id", Map.of("id", reRun.externalId()), Boolean.class))
        .isTrue();
    worker.poll();
    assertThat(renderer.renders).as("the re-run waits for its delay").hasSize(1);

    // A change to the case wants a bundle now: joining the delayed re-run makes it immediate.
    assertThat(submit("bundle", "case-1").externalId())
        .isEqualTo(reRun.externalId());
    assertThat(column(reRun.externalId(), "next_attempt_at")).isNull();
    assertThat(repository.findLatest("bundle", caseRef("case-1")).orElseThrow().externalId()).isEqualTo(reRun.externalId());
    worker.poll();
    assertThat(renderer.renders).hasSize(2);
    BundleJob secondReRun = queued("bundle", "case-1");
    assertThat(column(secondReRun.externalId(), "unavailable_requeues")).isEqualTo("2");

    jdbc.update("update bundling.bundle_job set next_attempt_at = null", Map.of());
    worker.poll();
    assertThat(renderer.renders).hasSize(3);
    assertThat(repository.findLatest("bundle", caseRef("case-1")).orElseThrow().externalId())
        .as("the bound of two re-runs is reached").isEqualTo(secondReRun.externalId());
  }

  @Test
  void aRenderMayRetryWhileAttemptsRemainAndIsFinalOnTheLastAttempt() {
    UUID id = UUID.randomUUID();
    submit(simpleRequest(id));
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(FakeRenderer.failure(
        new BundleGenerationException(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleStage.RESOLVE,
            "CDAM timed out", "Retry", List.of())));
    BundleJobWorker worker = directWorker(renderer, quickRetries(2), List.of());

    worker.poll();
    await().atMost(Duration.ofSeconds(5)).until(() -> {
      worker.poll();
      return job(id).state().terminal();
    });

    assertThat(renderer.attempts).containsExactly(RenderAttempt.RETRYABLE, RenderAttempt.FINAL);
    assertThat(job(id).state()).isEqualTo(BundleJobState.COMPLETED);
  }

  private BundleJob queued(String definition, String caseKey) {
    UUID id = jdbc.queryForObject("select external_id from bundling.bundle_job where state = 'QUEUED' "
        + "and definition = :definition and case_reference = :caseReference",
        Map.of("definition", definition, "caseReference", caseRef(caseKey)), UUID.class);
    return job(id);
  }

  private static BundleDocumentSelector twoDocumentSelector() {
    return selecting(context -> BundleRequest.builder().externalId(context.externalId())
        .title("Selected at execution").fileName("selected.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-kept"))
            .document(document("doc-gone")).build())
        .build());
  }

  @Test
  void coalescedSubmissionsCollapseOntoTheWaitingJobUntilItIsClaimed() throws Exception {
    BundleJob first = submit("hearing", "case-1");
    BundleJob second = submit("hearing", "case-1");
    BundleJob otherCase = submit("hearing", "case-2");
    assertThat(second.externalId()).isEqualTo(first.externalId());
    assertThat(otherCase.externalId()).isNotEqualTo(first.externalId());
    assertThat(submit("other", "case-1").externalId()).isNotEqualTo(first.externalId());
    assertThat(first.definition()).isEqualTo("hearing");
    assertThat(first.caseReference()).isEqualTo(caseRef("case-1"));
    assertThat(first.claimedAt()).isEmpty();
    assertThat(job(first.externalId()).coalescedSubmissions()).isEqualTo(1);

    CountDownLatch renderStarted = new CountDownLatch(1);
    CountDownLatch releaseRender = new CountDownLatch(1);
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(request -> {
      renderStarted.countDown();
      awaitQuietly(releaseRender);
      return renderer(tempDir).success(request, List.of());
    });
    List<BundleJob> completedJobs = new CopyOnWriteArrayList<>();
    BundleJobCompletionHandler handler = (job, selection, result) -> {
      completedJobs.add(job);
      return null;
    };
    BundleDocumentSelector selector = selecting(context -> BundleRequest.builder()
        .externalId(context.externalId()).title("Bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // One render slot: the first job is claimed and held mid-render.
      new BundleJobWorker(repository, renderer, selector, handler, quickRetries(3), List.of(),
          pool, 1, 1, Duration.ofMinutes(5)).poll();
      assertThat(renderStarted.await(10, TimeUnit.SECONDS)).isTrue();

      // A change while it renders queues one follow-up, which then absorbs later changes.
      BundleJob followUp = submit("hearing", "case-1");
      assertThat(followUp.externalId()).isNotEqualTo(first.externalId());
      assertThat(submit("hearing", "case-1").externalId())
          .isEqualTo(followUp.externalId());
      assertThat(repository.findLatest("hearing", caseRef("case-1")).orElseThrow().externalId())
          .isEqualTo(followUp.externalId());

      releaseRender.countDown();
      await().atMost(Duration.ofSeconds(10))
          .until(() -> job(first.externalId()).state().terminal());
    } finally {
      pool.shutdownNow();
    }

    // The handler can tell which subject the job was for, and saw when the attempt was claimed.
    BundleJob handled = completedJobs.get(0);
    assertThat(handled.claimedAt()).isPresent();
    assertThat(job(first.externalId()).claimedAt()).isEqualTo(handled.claimedAt());
    assertThat(jdbc.queryForObject("select count(*) from bundling.bundle_job "
        + "where definition = 'hearing' and case_reference = :reference",
        Map.of("reference", caseRef("case-1")), Integer.class)).isEqualTo(2);
  }

  @Test
  void joiningAWaitingJobMovesItsLastUpdateForwardEvenFromAnOlderTransaction() {
    // This transaction starts, so its now() is fixed, before the waiting job exists.
    BundleJob joined = tx.execute(status -> {
      jdbc.queryForObject("select now()", Map.of(), Object.class);
      UUID waiting = tx2().execute(inner ->
          submit("hearing", "case-7").externalId());
      BundleJob result = submit("hearing", "case-7");
      assertThat(result.externalId()).isEqualTo(waiting);
      return result;
    });
    assertThat(joined.lastUpdatedAt()).isAfterOrEqualTo(joined.submittedAt());
  }

  @Test
  void aCoalescedSubmissionWaitsForAConcurrentUncommittedOneAndJoinsIt() throws Exception {
    CountDownLatch firstSubmitted = new CountDownLatch(1);
    CountDownLatch commitFirst = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<UUID> first = pool.submit(() -> tx.execute(status -> {
        UUID id = submit("hearing", "case-9").externalId();
        firstSubmitted.countDown();
        awaitQuietly(commitFirst);
        return id;
      }));
      assertThat(firstSubmitted.await(10, TimeUnit.SECONDS)).isTrue();
      // Cannot see the uncommitted row, so it inserts, blocks on the unique index, then joins.
      Future<UUID> second = pool.submit(() -> tx.execute(status ->
          submit("hearing", "case-9").externalId()));
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
    UUID waiting = submit("hearing", "case-5").externalId();
    CountDownLatch joined = new CountDownLatch(1);
    CountDownLatch commitJoin = new CountDownLatch(1);
    FakeRenderer renderer = new FakeRenderer(tempDir);
    BundleDocumentSelector selector = selecting(context -> BundleRequest.builder()
        .externalId(context.externalId()).title("Bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build());
    BundleJobWorker worker = new BundleJobWorker(repository, renderer, selector, summaryHandler(),
        quickRetries(3), List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // A change joins the waiting job and has not committed yet.
      Future<UUID> joining = pool.submit(() -> tx.execute(status -> {
        UUID id = submit("hearing", "case-5").externalId();
        joined.countDown();
        awaitQuietly(commitJoin);
        return id;
      }));
      assertThat(joined.await(10, TimeUnit.SECONDS)).isTrue();

      // Its selector would not see the change yet, so the worker must leave the job alone.
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
    assertThat(job(waiting).coalescedSubmissions()).isEqualTo(1);
  }

  @Test
  void aSubmissionWhileTheJobAwaitsARetryQueuesAFreshJob() {
    UUID retrying = submit("hearing", "case-6").externalId();
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(FakeRenderer.failure(
        new BundleGenerationException(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
            BundleStage.RESOLVE, "transient", "retry", List.of())));
    BundleDocumentSelector selector = selecting(context -> BundleRequest.builder()
        .externalId(context.externalId()).title("Bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build());
    new BundleJobWorker(repository, renderer, selector, summaryHandler(),
        new BundleJobRetryPolicy(3, Duration.ofHours(1), 2.0, Duration.ofHours(1)), List.of(),
        Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();
    assertThat(job(retrying).state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(job(retrying).attempts()).isEqualTo(1);

    UUID fresh = submit("hearing", "case-6").externalId();

    assertThat(fresh).isNotEqualTo(retrying);
    // The fresh job reflects the newer state, so it is what a status read reports.
    assertThat(repository.findLatest("hearing", caseRef("case-6")).orElseThrow().externalId()).isEqualTo(fresh);
  }

  @Test
  void progressEventsArriveInOrderAndAThrowingListenerNeverBreaksTheJob() {
    UUID id = submit(simpleRequest(UUID.randomUUID())).externalId();
    List<BundleProgressEvent> events = new CopyOnWriteArrayList<>();
    BundleProgressListener throwing = event -> {
      throw new IllegalStateException("listener bug");
    };

    directWorker(new FakeRenderer(tempDir), quickRetries(3), List.of(throwing, events::add))
        .poll();

    assertThat(job(id).state()).isEqualTo(BundleJobState.COMPLETED);
    assertThat(events).containsExactly(
        new BundleProgressEvent(id, BundleJobState.IN_PROGRESS, 0, 1),
        new BundleProgressEvent(id, BundleJobState.COMPLETED, 1, 1));
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
        "sha-" + name, 1, new BundleJobJson().write(request), "[]", "[]", "[]", null);
  }

  /** Queues a job, under the request's id, for a case whose selection is that request. */
  private static BundleJob submit(BundleRequest request) {
    long caseReference = caseRef(request.externalId().toString());
    REQUESTS.put(caseReference, request);
    return job(repository.insertOrJoin(request.externalId(), "test", caseReference, null, 0, 0));
  }

  /** Queues a job for the case, as a change to it does. */
  private static BundleJob submit(String definition, String caseKey) {
    return job(repository.insertOrJoin(UUID.randomUUID(), definition, caseRef(caseKey), null, 0, 0));
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
    return repository.refresh(definition);
  }

  private static BundleDocumentSelector asSubmitted() {
    return selecting(job -> REQUESTS.get(job.caseReference()));
  }

  /** Wraps a request selection as the selection of a case. */
  private static BundleDocumentSelector selecting(Function<BundleJob, BundleRequest> request) {
    return job -> repository.selectedFor(job.caseReference(), request.apply(job));
  }

  private static StoredBundleDocument stored() {
    UUID id = UUID.randomUUID();
    return new StoredBundleDocument(id, "http://dm/" + id, "http://dm/" + id + "/binary");
  }

  private static BundleJobCompletionHandler summaryHandler() {
    return (job, selection, result) -> null;
  }

  private static BundleJobWorker directWorker(FakeRenderer renderer, BundleJobRetryPolicy policy,
      List<BundleProgressListener> listeners) {
    return new BundleJobWorker(repository, renderer, asSubmitted(),
        summaryHandler(), policy, listeners, Runnable::run, 5, 5, Duration.ofMinutes(5));
  }

  private static BundleJobWorker directWorker(FakeRenderer renderer) {
    return directWorker(renderer, quickRetries(3), List.of());
  }

  // A separate transaction that commits independently of any outer one.
  private static TransactionTemplate tx2() {
    TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
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

  private static BundleJob job(UUID id) {
    return repository.find(id).orElseThrow();
  }

  private static String column(UUID id, String column) {
    return jdbc.queryForObject("select " + column + "::text from bundling.bundle_job "
        + "where external_id = :id", Map.of("id", id), String.class);
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
  private static BundleRequest fullRequest(UUID id) {
    return BundleRequest.builder().externalId(id).title("Final hearing bundle")
        .fileName("final-hearing-bundle.pdf")
        .presentation(uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation.courtDefault()
            .withDocumentCoverSheets(true)
            .withWatermark(uk.gov.hmcts.ccd.sdk.bundling.api.WatermarkPreset.allPages("hmcts-logo")))
        .coverPage(new uk.gov.hmcts.ccd.sdk.bundling.api.CoverPage(
            "FL-FRM-GOR-ENG-12345.docx", java.util.Map.of("caseReference", "1234")))
        .root(BundleSection.builder("Case file")
            .document(BundleDocument.builder().id("doc-1").title("Application form")
                .date(java.time.LocalDate.of(2026, 3, 14))
                .reference(new DocumentReference("case-documents", "d1")).build())
            .section(BundleSection.builder("Evidence")
                .document(BundleDocument.builder().id("doc-2").title("Medical report")
                    .confidential(true).reference(new DocumentReference("case-documents", "d2"))
                    .build())
                .document(BundleDocument.builder().id("doc-3").title("Hearing recording")
                    .reference(new DocumentReference("media-store", "m1"))
                    .media(uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder.builder()
                        .mediaType("audio/mpeg").accessUrl("https://media.example/m1")
                        .duration(Duration.ofMinutes(42)).build())
                    .build())
                .build())
            .build())
        .build();
  }

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
