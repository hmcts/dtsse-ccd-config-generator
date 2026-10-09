package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.sql.DataSource;
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
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@Testcontainers
class BundleJobOutboxIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");
  private static final BundleExecutionContext CONTEXT = BundleExecutionContext.builder()
      .caseReference("1234567890123456").initiator("scheduler").attribute("hearingId", "H-77")
      .build();
  // The application's own migrations, which the runtime's strategy runs after the libraries'.
  private static final String CONSUMER_MIGRATIONS =
      "spring.flyway.locations=classpath:consumer-db/migration";

  private static DataSource dataSource;
  private static NamedParameterJdbcTemplate jdbc;
  private static BundleJobRepository repository;
  private static OutboxBundleJobService service;
  private static TransactionTemplate tx;

  @TempDir
  Path tempDir;

  @BeforeAll
  static void migrateThroughTheSdkFlywayStrategy() {
    dataSource = dataSourceFor(POSTGRES.getDatabaseName());
    sdkMigrationRunner(dataSource).run(context -> assertThat(context).hasNotFailed());
    jdbc = new NamedParameterJdbcTemplate(dataSource);
    repository = new BundleJobRepository(jdbc);
    service = new OutboxBundleJobService(repository);
    tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @BeforeEach
  void cleanTable() {
    jdbc.update("delete from bundling.bundle_job", Map.of());
  }

  @Test
  void submitThenPollRunsThroughInProgressToCompletedPersistingTheHandlersSummary()
      throws Exception {
    UUID id = UUID.randomUUID();
    BundleJob submitted = service.submit(fullRequest(id), CONTEXT);
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
    BundleJobCompletionHandler handler = (job, jobContext, request, result) -> {
      handled.add(request);
      assertThat(job.state()).isEqualTo(BundleJobState.IN_PROGRESS);
      try (InputStream pdf = result.artifact().open()) {
        return Map.of("bytes", pdf.readAllBytes().length, "pages", result.pageCount());
      }
    };
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      BundleJobWorker worker = new BundleJobWorker(repository, renderer,
          BundleDocumentSelector.asSubmitted(), handler, quickRetries(3), List.of(), pool, 5, 5,
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
    assertThat(completed.result().orElseThrow()).contains("\"bytes\"").contains("\"pages\": 3");
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
    service.submit(simpleRequest(warned), CONTEXT);
    directWorker(new FakeRenderer(tempDir).onNextRender(request -> renderer(tempDir)
        .success(request, List.of(BundleWarning.of("EMPTY_SECTION_PAGE_INCLUDED", "empty")))))
        .poll();
    assertThat(job(warned).state()).isEqualTo(BundleJobState.COMPLETED_WITH_WARNINGS);
  }

  @Test
  void repeatedExternalIdReturnsTheExistingJobAndSubmitJoinsTheCallersTransaction() {
    UUID id = UUID.randomUUID();
    service.submit(simpleRequest(id), CONTEXT);
    assertThat(service.submit(simpleRequest(id), CONTEXT).state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(jdbc.queryForObject("select count(*) from bundling.bundle_job", Map.of(), Integer.class))
        .isEqualTo(1);
    FakeRenderer renderer = new FakeRenderer(tempDir);
    BundleJobWorker worker = directWorker(renderer);
    worker.poll();
    assertThat(service.submit(simpleRequest(id), CONTEXT).state())
        .isEqualTo(BundleJobState.COMPLETED);
    worker.poll();
    assertThat(renderer.renders).hasSize(1);

    UUID rolledBack = UUID.randomUUID();
    tx.execute(status -> {
      service.submit(simpleRequest(rolledBack), CONTEXT);
      assertThat(service.find(rolledBack)).isPresent();
      status.setRollbackOnly();
      return null;
    });
    assertThat(service.find(rolledBack)).isEmpty();
  }

  @Test
  void contendingWorkersSkipEachOthersLockedRowsAndNeverDoubleClaim() throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      ids.add(service.submit(simpleRequest(UUID.randomUUID()), CONTEXT).externalId());
    }
    CountDownLatch firstClaimHeld = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // Worker A claims three rows and holds its transaction (and so its row locks) open.
      Future<List<ClaimedBundleJob>> first = pool.submit(() -> tx.execute(status -> {
        List<ClaimedBundleJob> claimed = repository.claim(3, "worker-a", Duration.ofMinutes(5), 3);
        firstClaimHeld.countDown();
        awaitQuietly(release);
        return claimed;
      }));
      assertThat(firstClaimHeld.await(10, TimeUnit.SECONDS)).isTrue();
      // B returning at all proves SKIP LOCKED did not block; A releases only after B is done.
      List<ClaimedBundleJob> second = repository.claim(6, "worker-b", Duration.ofMinutes(5), 3);
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
    service.submit(simpleRequest(UUID.randomUUID()), CONTEXT);
    List<ClaimedBundleJob> first = repository.claim(1, "worker-a", Duration.ofMillis(100), 3);
    assertThat(first).hasSize(1);
    assertThat(first.get(0).job().attempts()).isEqualTo(1);
    assertThat(repository.claim(1, "worker-b", Duration.ofMinutes(5), 3)).isEmpty();

    Thread.sleep(250);
    List<ClaimedBundleJob> reclaimed = repository.claim(1, "worker-b", Duration.ofMinutes(5), 3);
    assertThat(reclaimed).hasSize(1);
    assertThat(reclaimed.get(0).job().attempts()).isEqualTo(2);
    UUID id = reclaimed.get(0).job().externalId();
    assertThat(column(id, "lease_owner")).isEqualTo("worker-b");

    // The stale worker's completion, failure and requeue are all rejected; B's outcome stands.
    assertThat(repository.markCompleted(id, BundleJobState.COMPLETED, "{}", null, "worker-a")).isFalse();
    assertThat(repository.markFailed(id, BundleErrorCode.ASSEMBLY_FAILED, "late", "[]", null,
        "worker-a")).isFalse();
    assertThat(repository.requeueForRetry(id, java.time.Instant.now(), "[]", "worker-a"))
        .isFalse();
    assertThat(job(id).state()).isEqualTo(BundleJobState.IN_PROGRESS);
    assertThat(repository.markCompleted(id, BundleJobState.COMPLETED, "{\"by\":\"b\"}", null, "worker-b"))
        .isTrue();
    assertThat(job(id).result()).contains("{\"by\": \"b\"}");
  }

  @Test
  void transientFailureRequeuesWithBackoffThenBoundedRetryFailsCarryingTheHistory()
      throws Exception {
    UUID id = service.submit(simpleRequest(UUID.randomUUID()), CONTEXT).externalId();
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
    UUID notFound = service.submit(simpleRequest(UUID.randomUUID()), CONTEXT).externalId();
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
    UUID missingTemplate = service.submit(simpleRequest(UUID.randomUUID()), CONTEXT).externalId();
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
    UUID unstored = service.submit(simpleRequest(UUID.randomUUID()), CONTEXT).externalId();
    new BundleJobWorker(repository, new FakeRenderer(tempDir), BundleDocumentSelector.asSubmitted(),
        (job, jobContext, request, result) -> {
          throw new IllegalStateException("signed-url=https://secret");
        }, quickRetries(3), List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();
    BundleJobFailure completion = job(unstored).failure().orElseThrow();
    assertThat(completion.code()).isEqualTo(BundleErrorCode.COMPLETION_FAILED);
    assertThat(completion.message()).contains("IllegalStateException").doesNotContain("secret");
    assertThat(Files.list(tempDir)).isEmpty();
  }

  @Test
  void unreadableStoredRequestFailsWithoutReachingTheRenderer() {
    UUID corrupt = service.submit(simpleRequest(UUID.randomUUID()), CONTEXT).externalId();
    jdbc.update("update bundling.bundle_job set request = '{\"title\":\"no root\"}'::jsonb "
        + "where external_id = :id", Map.of("id", corrupt));
    UUID futureVersion = service.submit(simpleRequest(UUID.randomUUID()), CONTEXT).externalId();
    jdbc.update("update bundling.bundle_job set request_version = 99 where external_id = :id",
        Map.of("id", futureVersion));
    FakeRenderer renderer = new FakeRenderer(tempDir);

    directWorker(renderer).poll();

    assertThat(job(corrupt).state()).isEqualTo(BundleJobState.FAILED);
    assertThat(job(corrupt).failure().orElseThrow().code())
        .isEqualTo(BundleErrorCode.JOB_REQUEST_UNREADABLE);
    assertThat(job(corrupt).failure().orElseThrow().message()).contains(corrupt.toString());
    // A row written by a newer worker is left for that worker, never claimed by this one.
    assertThat(job(futureVersion).state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(column(futureVersion, "lease_owner")).isNull();
    assertThat(renderer.renders).isEmpty();
  }

  @Test
  void selectorParametersSubmissionCompilesTheRequestAtExecutionTime() {
    UUID id = UUID.randomUUID();
    service.submit(id, Map.of("bundleTitle", "Compiled at execution"), CONTEXT);
    assertThat(column(id, "request")).isNull();
    assertThat(column(id, "selector_parameters")).contains("bundleTitle");
    BundleDocumentSelector selector = context -> {
      assertThat(context.submittedRequest()).isEmpty();
      assertThat(context.executionContext().attributes()).containsEntry("hearingId", "H-77");
      return BundleRequest.builder().externalId(context.externalId())
          .title(context.parameters().get("bundleTitle")).fileName("compiled.pdf")
          .root(BundleSection.builder("Case file").document(document("doc-late")).build())
          .build();
    };
    FakeRenderer renderer = new FakeRenderer(tempDir);

    new BundleJobWorker(repository, renderer, selector, summaryHandler(), quickRetries(3),
        List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();

    assertThat(renderer.renders).extracting(BundleRequest::title)
        .containsExactly("Compiled at execution");
    assertThat(job(id).state()).isEqualTo(BundleJobState.COMPLETED);
    assertThat(job(id).result().orElseThrow()).contains("Compiled at execution");
  }

  @Test
  void aCompletedJobRecordsWhatItRenderedFromTheRequestTheSelectorCompiled() {
    BundleJob submitted = service.submitCoalesced("case-1:bundle", Map.of(), CONTEXT);
    assertThat(service.findReport(submitted.externalId())).isEmpty();
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(request ->
        renderer(tempDir).success(request, List.of(), List.of("doc-gone")));

    new BundleJobWorker(repository, renderer, twoDocumentSelector(), summaryHandler(), quickRetries(3),
        List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();

    BundleJobReport report = service.findReport(submitted.externalId()).orElseThrow();
    assertThat(report.request().title()).isEqualTo("Selected at execution");
    assertThat(report.request().allDocuments()).extracting(BundleDocument::id)
        .containsExactly("doc-kept", "doc-gone");
    assertThat(report.outcome()).isEqualTo(uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome.COMPLETED_WITH_WARNINGS);
    assertThat(report.fileName()).isEqualTo("selected.pdf");
    assertThat(report.sha256()).isEqualTo("sha-bundle");
    assertThat(report.pageCount()).isEqualTo(1);
    assertThat(report.documents()).extracting(DocumentResult::documentId).containsExactly("doc-kept");
    assertThat(report.missingDocuments()).singleElement().satisfies(missing -> {
      assertThat(missing.documentId()).isEqualTo("doc-gone");
      assertThat(missing.reason()).isEqualTo(MissingDocumentReason.NOT_FOUND);
      assertThat(missing.startPage()).isEqualTo(2);
    });
    // Only an unavailable document is worth re-running for.
    assertThat(service.findLatest("case-1:bundle").orElseThrow().externalId())
        .isEqualTo(submitted.externalId());
  }

  @Test
  void aBundleMissingTemporarilyUnavailableDocumentsIsReRunLaterUntilTheBound() {
    BundleJob submitted = service.submitCoalesced("case-1:bundle", Map.of("caseId", "1"), CONTEXT);
    FakeRenderer renderer = new FakeRenderer(tempDir);
    for (int i = 0; i < 3; i++) {
      renderer.onNextRender(request -> renderer(tempDir).success(request, List.of(), List.of(),
          List.of("doc-gone")));
    }
    BundleJobWorker worker = new BundleJobWorker(repository, renderer, twoDocumentSelector(), summaryHandler(),
        new BundleJobRetryPolicy(3, Duration.ofMillis(1), 2.0, Duration.ofMillis(2), Duration.ofMinutes(15), 2),
        List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5));

    worker.poll();

    BundleJob reRun = service.findLatest("case-1:bundle").orElseThrow();
    assertThat(reRun.externalId()).isNotEqualTo(submitted.externalId());
    assertThat(reRun.state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(column(reRun.externalId(), "selector_parameters")).contains("caseId");
    assertThat(column(reRun.externalId(), "execution_context"))
        .contains("\"bundling.unavailableRequeues\": \"1\"").contains("hearingId");
    assertThat(jdbc.queryForObject("select next_attempt_at > now() + interval '14 minutes' "
        + "from bundling.bundle_job where external_id = :id", Map.of("id", reRun.externalId()), Boolean.class))
        .isTrue();
    worker.poll();
    assertThat(renderer.renders).as("the re-run waits for its delay").hasSize(1);

    // A change to the case wants a bundle now: joining the delayed re-run makes it immediate.
    assertThat(service.submitCoalesced("case-1:bundle", Map.of("caseId", "1"), CONTEXT).externalId())
        .isEqualTo(reRun.externalId());
    assertThat(column(reRun.externalId(), "next_attempt_at")).isNull();
    worker.poll();
    assertThat(renderer.renders).hasSize(2);
    BundleJob secondReRun = service.findLatest("case-1:bundle").orElseThrow();
    assertThat(column(secondReRun.externalId(), "execution_context"))
        .contains("\"bundling.unavailableRequeues\": \"2\"");

    jdbc.update("update bundling.bundle_job set next_attempt_at = null", Map.of());
    worker.poll();
    assertThat(renderer.renders).hasSize(3);
    assertThat(service.findLatest("case-1:bundle").orElseThrow().externalId())
        .as("the bound of two re-runs is reached").isEqualTo(secondReRun.externalId());
  }

  @Test
  void aRenderMayRetryWhileAttemptsRemainAndIsFinalOnTheLastAttempt() {
    UUID id = UUID.randomUUID();
    service.submit(simpleRequest(id), CONTEXT);
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

  private static BundleDocumentSelector twoDocumentSelector() {
    return context -> BundleRequest.builder().externalId(context.externalId())
        .title("Selected at execution").fileName("selected.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-kept"))
            .document(document("doc-gone")).build())
        .build();
  }

  @Test
  void coalescedSubmissionsCollapseOntoTheWaitingJobUntilItIsClaimed() throws Exception {
    BundleJob first = service.submitCoalesced("case-1:hearing", Map.of("n", "1"), CONTEXT);
    BundleJob second = service.submitCoalesced("case-1:hearing", Map.of("n", "2"), CONTEXT);
    BundleJob otherCase = service.submitCoalesced("case-2:hearing", Map.of(), CONTEXT);
    assertThat(second.externalId()).isEqualTo(first.externalId());
    assertThat(otherCase.externalId()).isNotEqualTo(first.externalId());
    assertThat(first.coalesceKey()).contains("case-1:hearing");
    assertThat(first.claimedAt()).isEmpty();
    assertThat(job(first.externalId()).coalescedSubmissions()).isEqualTo(1);
    // The waiting job keeps what it was first submitted with.
    assertThat(column(first.externalId(), "selector_parameters")).contains("\"1\"");
    assertThatThrownBy(() -> service.submitCoalesced("k".repeat(256), Map.of(), CONTEXT))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("255");
    // The limit is characters, as in the varchar(255) column: 255 emoji (510 UTF-16 units) fit.
    String emojiKey = "\uD83D\uDCC4".repeat(255);
    assertThat(service.submitCoalesced(emojiKey, Map.of(), CONTEXT).coalesceKey()).contains(emojiKey);

    CountDownLatch renderStarted = new CountDownLatch(1);
    CountDownLatch releaseRender = new CountDownLatch(1);
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(request -> {
      renderStarted.countDown();
      awaitQuietly(releaseRender);
      return renderer(tempDir).success(request, List.of());
    });
    List<BundleJob> completedJobs = new CopyOnWriteArrayList<>();
    List<BundleJobContext> completedContexts = new CopyOnWriteArrayList<>();
    BundleJobCompletionHandler handler = (job, jobContext, request, result) -> {
      completedJobs.add(job);
      completedContexts.add(jobContext);
      return null;
    };
    BundleDocumentSelector selector = context -> BundleRequest.builder()
        .externalId(context.externalId()).title("Bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // One render slot: the first job is claimed and held mid-render.
      new BundleJobWorker(repository, renderer, selector, handler, quickRetries(3), List.of(),
          pool, 1, 1, Duration.ofMinutes(5)).poll();
      assertThat(renderStarted.await(10, TimeUnit.SECONDS)).isTrue();

      // A change while it renders queues one follow-up, which then absorbs later changes.
      BundleJob followUp = service.submitCoalesced("case-1:hearing", Map.of(), CONTEXT);
      assertThat(followUp.externalId()).isNotEqualTo(first.externalId());
      assertThat(service.submitCoalesced("case-1:hearing", Map.of(), CONTEXT).externalId())
          .isEqualTo(followUp.externalId());
      assertThat(service.findLatest("case-1:hearing").orElseThrow().externalId())
          .isEqualTo(followUp.externalId());

      releaseRender.countDown();
      await().atMost(Duration.ofSeconds(10))
          .until(() -> job(first.externalId()).state().terminal());
    } finally {
      pool.shutdownNow();
    }

    // The handler can tell which subject the job was for, and saw when the attempt was claimed.
    assertThat(completedContexts.get(0).parameters()).containsEntry("n", "1");
    assertThat(completedContexts.get(0).executionContext().caseReference())
        .contains("1234567890123456");
    BundleJob handled = completedJobs.get(0);
    assertThat(handled.claimedAt()).isPresent();
    assertThat(job(first.externalId()).claimedAt()).isEqualTo(handled.claimedAt());
    assertThat(jdbc.queryForObject("select count(*) from bundling.bundle_job "
        + "where coalesce_key = 'case-1:hearing'", Map.of(), Integer.class)).isEqualTo(2);
  }

  @Test
  void joiningAWaitingJobMovesItsLastUpdateForwardEvenFromAnOlderTransaction() {
    // This transaction starts, so its now() is fixed, before the waiting job exists.
    BundleJob joined = tx.execute(status -> {
      jdbc.queryForObject("select now()", Map.of(), Object.class);
      UUID waiting = tx2().execute(inner ->
          service.submitCoalesced("case-7:hearing", Map.of(), CONTEXT).externalId());
      BundleJob result = service.submitCoalesced("case-7:hearing", Map.of(), CONTEXT);
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
        UUID id = service.submitCoalesced("case-9:hearing", Map.of(), CONTEXT).externalId();
        firstSubmitted.countDown();
        awaitQuietly(commitFirst);
        return id;
      }));
      assertThat(firstSubmitted.await(10, TimeUnit.SECONDS)).isTrue();
      // Cannot see the uncommitted row, so it inserts, blocks on the unique index, then joins.
      Future<UUID> second = pool.submit(() -> tx.execute(status ->
          service.submitCoalesced("case-9:hearing", Map.of(), CONTEXT).externalId()));
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
    UUID waiting = service.submitCoalesced("case-5:hearing", Map.of(), CONTEXT).externalId();
    CountDownLatch joined = new CountDownLatch(1);
    CountDownLatch commitJoin = new CountDownLatch(1);
    FakeRenderer renderer = new FakeRenderer(tempDir);
    BundleDocumentSelector selector = context -> BundleRequest.builder()
        .externalId(context.externalId()).title("Bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build();
    BundleJobWorker worker = new BundleJobWorker(repository, renderer, selector, summaryHandler(),
        quickRetries(3), List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // A change joins the waiting job and has not committed yet.
      Future<UUID> joining = pool.submit(() -> tx.execute(status -> {
        UUID id = service.submitCoalesced("case-5:hearing", Map.of(), CONTEXT).externalId();
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
    UUID retrying = service.submitCoalesced("case-6:hearing", Map.of(), CONTEXT).externalId();
    FakeRenderer renderer = new FakeRenderer(tempDir).onNextRender(FakeRenderer.failure(
        new BundleGenerationException(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
            BundleStage.RESOLVE, "transient", "retry", List.of())));
    BundleDocumentSelector selector = context -> BundleRequest.builder()
        .externalId(context.externalId()).title("Bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Case file").document(document("doc-1")).build()).build();
    new BundleJobWorker(repository, renderer, selector, summaryHandler(),
        new BundleJobRetryPolicy(3, Duration.ofHours(1), 2.0, Duration.ofHours(1)), List.of(),
        Runnable::run, 5, 5, Duration.ofMinutes(5)).poll();
    assertThat(job(retrying).state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(job(retrying).attempts()).isEqualTo(1);

    UUID fresh = service.submitCoalesced("case-6:hearing", Map.of(), CONTEXT).externalId();

    assertThat(fresh).isNotEqualTo(retrying);
    // The fresh job reflects the newer state, so it is what a status read reports.
    assertThat(service.findLatest("case-6:hearing").orElseThrow().externalId()).isEqualTo(fresh);
  }

  @Test
  void progressEventsArriveInOrderAndAThrowingListenerNeverBreaksTheJob() {
    UUID id = service.submit(simpleRequest(UUID.randomUUID()), CONTEXT).externalId();
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
    assertThat(tables(consumer, "bundling")).containsExactly("bundle_job", "flyway_schema_history");
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

  private static BundleJobCompletionHandler summaryHandler() {
    return (job, jobContext, request, result) -> Map.of("title", request.title(), "pages", result.pageCount());
  }

  private static BundleJobWorker directWorker(FakeRenderer renderer, BundleJobRetryPolicy policy,
      List<BundleProgressListener> listeners) {
    return new BundleJobWorker(repository, renderer, BundleDocumentSelector.asSubmitted(),
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
    return service.find(id).orElseThrow();
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
                "test", page));
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
