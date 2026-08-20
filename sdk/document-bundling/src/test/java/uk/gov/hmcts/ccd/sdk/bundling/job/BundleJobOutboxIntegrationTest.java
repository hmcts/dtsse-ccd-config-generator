package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundle;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;

/**
 * The durable job runner against real PostgreSQL and real JDBC; only the renderer boundary is
 * faked, per the testing strategy.
 */
@Testcontainers
class BundleJobOutboxIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static NamedParameterJdbcTemplate jdbc;
  private static BundleJobRepository repository;
  private static OutboxBundleJobService service;
  private static TransactionTemplate transactionTemplate;

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
    transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @BeforeEach
  void cleanTable() {
    jdbc.update("delete from ccd_bundle_job", Map.of());
  }

  private static BundleJobRetryPolicy quickRetries(int maxAttempts) {
    return new BundleJobRetryPolicy(maxAttempts, Duration.ofMillis(1), 2.0, Duration.ofMillis(2));
  }

  private static BundleJobWorker directWorker(FakeBundleRenderer renderer,
      BundleJobRetryPolicy policy, List<BundleProgressListener> listeners) {
    return new BundleJobWorker(repository, renderer, BundleDocumentSelector.asSubmitted(),
        policy, listeners, Runnable::run, 5, 5, Duration.ofMinutes(5));
  }

  private static BundleJobWorker directWorker(FakeBundleRenderer renderer) {
    return directWorker(renderer, quickRetries(3), List.of());
  }

  private BundleJob job(UUID externalId) {
    return service.find(externalId).orElseThrow();
  }

  private String column(UUID externalId, String column) {
    return jdbc.queryForObject(
        "select " + column + "::text from ccd_bundle_job where external_id = :id",
        Map.of("id", externalId), String.class);
  }

  private int completedCount() {
    Integer completed = jdbc.queryForObject(
        "select count(*) from ccd_bundle_job where state = 'COMPLETED'",
        Map.of(), Integer.class);
    return completed == null ? 0 : completed;
  }

  @Test
  void submitThenPollRunsTheJobThroughAnInProgressStateToCompleted() throws Exception {
    UUID externalId = UUID.randomUUID();
    BundleJob submitted =
        service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());
    assertThat(submitted.externalId()).isEqualTo(externalId);
    assertThat(submitted.state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(submitted.attempts()).isZero();

    CountDownLatch renderStarted = new CountDownLatch(1);
    CountDownLatch releaseRender = new CountDownLatch(1);
    FakeBundleRenderer renderer = new FakeBundleRenderer().onNextRender(request -> {
      renderStarted.countDown();
      try {
        releaseRender.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return BundleJobFixtures.resultFor(request, BundleOutcome.COMPLETED);
    });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      BundleJobWorker worker = new BundleJobWorker(repository, renderer,
          BundleDocumentSelector.asSubmitted(), quickRetries(3), List.of(), pool, 5, 5,
          Duration.ofMinutes(5));
      worker.poll();
      assertThat(renderStarted.await(10, TimeUnit.SECONDS)).isTrue();

      // The claim is visible as an in-progress state under a lease while the render runs.
      assertThat(job(externalId).state()).isEqualTo(BundleJobState.RESOLVING);
      assertThat(column(externalId, "lease_owner")).startsWith("bundle-job-worker-");
      assertThat(column(externalId, "lease_expires_at")).isNotNull();

      releaseRender.countDown();
      await().atMost(Duration.ofSeconds(10))
          .until(() -> job(externalId).state().terminal());
    } finally {
      pool.shutdownNow();
    }

    BundleJob completed = job(externalId);
    assertThat(completed.state()).isEqualTo(BundleJobState.COMPLETED);
    assertThat(completed.attempts()).isEqualTo(1);
    assertThat(completed.failure()).isEmpty();

    // The stored result JSON reads back as a CcdBundle.
    CcdBundle output = completed.output().orElseThrow();
    assertThat(output.getTitle()).isEqualTo("Hearing bundle");
    assertThat(output.getStitchStatus()).isEqualTo("DONE");
    assertThat(output.getStitchedDocument().getUrl()).isEqualTo("http://dm-store/documents/out");

    // The lease is released on completion.
    assertThat(column(externalId, "lease_owner")).isNull();
  }

  @Test
  void aWarningCarryingRenderCompletesWithWarnings() {
    UUID externalId = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());
    FakeBundleRenderer renderer = new FakeBundleRenderer().onNextRender(
        request -> BundleJobFixtures.resultFor(request, BundleOutcome.COMPLETED_WITH_WARNINGS));

    directWorker(renderer).poll();

    assertThat(job(externalId).state()).isEqualTo(BundleJobState.COMPLETED_WITH_WARNINGS);
  }

  @Test
  void aRepeatedExternalIdReturnsTheExistingJobAndNeverRendersASecondBundle() {
    UUID externalId = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());
    BundleJob again =
        service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());

    assertThat(again.externalId()).isEqualTo(externalId);
    assertThat(again.state()).isEqualTo(BundleJobState.QUEUED);
    Integer rows = jdbc.queryForObject(
        "select count(*) from ccd_bundle_job", Map.of(), Integer.class);
    assertThat(rows).isEqualTo(1);

    FakeBundleRenderer renderer = new FakeBundleRenderer();
    BundleJobWorker worker = directWorker(renderer);
    worker.poll();
    assertThat(renderer.renders).hasSize(1);

    // Resubmitting after completion returns the completed job; nothing re-queues.
    BundleJob completed =
        service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());
    assertThat(completed.state()).isEqualTo(BundleJobState.COMPLETED);
    worker.poll();
    assertThat(renderer.renders).hasSize(1);
  }

  @Test
  void submissionJoinsTheCallersTransactionAndRollsBackWithIt() {
    UUID externalId = UUID.randomUUID();

    transactionTemplate.execute(status -> {
      service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());
      // Visible inside the caller's transaction...
      assertThat(service.find(externalId)).isPresent();
      status.setRollbackOnly();
      return null;
    });

    // ...and gone with it: the insert was part of the caller's transaction, not its own.
    assertThat(service.find(externalId)).isEmpty();
  }

  @Test
  void contendingWorkersSkipEachOthersLockedRowsAndNeverDoubleClaim() throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      service.submit(BundleJobFixtures.simpleRequest(id), BundleJobFixtures.context());
    }

    CountDownLatch firstClaimHeld = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // Worker A claims three rows and holds its transaction (and so its row locks) open.
      Future<List<ClaimedBundleJob>> first = pool.submit(() ->
          transactionTemplate.execute(status -> {
            List<ClaimedBundleJob> claimed =
                repository.claim(3, "worker-a", Duration.ofMinutes(5), 3);
            firstClaimHeld.countDown();
            try {
              release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return claimed;
          }));
      assertThat(firstClaimHeld.await(10, TimeUnit.SECONDS)).isTrue();

      // Worker B claims while A's locks are held: SKIP LOCKED means it neither blocks (this
      // call returning at all is the proof - A only releases after B finishes) nor takes A's
      // rows.
      List<ClaimedBundleJob> second = repository.claim(6, "worker-b", Duration.ofMinutes(5), 3);

      release.countDown();
      List<ClaimedBundleJob> firstClaimed = first.get(10, TimeUnit.SECONDS);

      assertThat(firstClaimed).hasSize(3);
      assertThat(second).hasSize(3);
      Set<UUID> claimed = new HashSet<>();
      firstClaimed.forEach(job -> claimed.add(job.externalId()));
      second.forEach(job -> claimed.add(job.externalId()));
      assertThat(claimed).containsExactlyInAnyOrderElementsOf(ids);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void anExpiredLeaseIsReclaimableAndIncrementsAttempts() throws Exception {
    UUID externalId = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());

    List<ClaimedBundleJob> first = repository.claim(1, "worker-a", Duration.ofMillis(100), 3);
    assertThat(first).hasSize(1);
    assertThat(first.get(0).attempts()).isEqualTo(1);

    // While the lease is live the row is not claimable by anyone else.
    assertThat(repository.claim(1, "worker-b", Duration.ofMinutes(5), 3)).isEmpty();

    Thread.sleep(250);

    // Past expiry the abandoned claim is recoverable, and the reclaim counts as an attempt.
    List<ClaimedBundleJob> reclaimed = repository.claim(1, "worker-b", Duration.ofMinutes(5), 3);
    assertThat(reclaimed).hasSize(1);
    assertThat(reclaimed.get(0).externalId()).isEqualTo(externalId);
    assertThat(reclaimed.get(0).attempts()).isEqualTo(2);
    assertThat(column(externalId, "lease_owner")).isEqualTo("worker-b");
  }

  @Test
  void aTransientFailureRequeuesWithBackoffThenSucceeds() throws Exception {
    UUID externalId = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());
    FakeBundleRenderer renderer = new FakeBundleRenderer()
        .onNextRender(FakeBundleRenderer.failure(new BundleGenerationException(
            BundleErrorCode.STORAGE_FAILED, BundleStage.STORE,
            "CDAM rejected the upload.", "Retry when CDAM recovers.", List.of())));
    List<BundleProgressEvent> events = new CopyOnWriteArrayList<>();
    BundleJobWorker worker = directWorker(renderer, quickRetries(3), List.of(events::add));

    worker.poll();
    BundleJob requeued = job(externalId);
    assertThat(requeued.state()).isEqualTo(BundleJobState.QUEUED);
    assertThat(requeued.attempts()).isEqualTo(1);
    assertThat(requeued.failure()).isEmpty();
    assertThat(column(externalId, "next_attempt_at")).isNotNull();
    assertThat(column(externalId, "transient_history")).contains("STORAGE_FAILED");

    Thread.sleep(50);
    worker.poll();

    BundleJob completed = job(externalId);
    assertThat(completed.state()).isEqualTo(BundleJobState.COMPLETED);
    assertThat(completed.attempts()).isEqualTo(2);
    assertThat(events).extracting(BundleProgressEvent::state).containsExactly(
        BundleJobState.RESOLVING, BundleJobState.QUEUED,
        BundleJobState.RESOLVING, BundleJobState.COMPLETED);
  }

  @Test
  void boundedRetryExhaustsToFailedCarryingTheTransientHistory() throws Exception {
    UUID externalId = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());
    BundleGenerationException transientFailure = new BundleGenerationException(
        BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, BundleStage.RESOLVE,
        "The source store returned 503.", "Check dm-store health.",
        List.of(new DocumentFailure("doc-1", new DocumentReference("case-documents", "d1"),
            BundleErrorCode.DOCUMENT_RESOLUTION_FAILED, "dm-store 503")));
    FakeBundleRenderer renderer = new FakeBundleRenderer()
        .onNextRender(FakeBundleRenderer.failure(transientFailure))
        .onNextRender(FakeBundleRenderer.failure(transientFailure));
    BundleJobWorker worker = directWorker(renderer, quickRetries(2), List.of());

    worker.poll();
    Thread.sleep(50);
    worker.poll();

    BundleJob failed = job(externalId);
    assertThat(failed.state()).isEqualTo(BundleJobState.FAILED);
    assertThat(failed.attempts()).isEqualTo(2);
    BundleJobFailure failure = failed.failure().orElseThrow();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    assertThat(failure.message())
        .contains("Retries exhausted after 2 attempt(s)")
        .contains("attempt 1 at")
        .contains("attempt 2 at")
        .contains("DOCUMENT_RESOLUTION_FAILED");
    assertThat(failure.documentFailures()).hasSize(1);
    assertThat(failure.documentFailures().get(0).documentId()).isEqualTo("doc-1");
    assertThat(column(externalId, "transient_history")).contains("attempt");

    // Exhausted means exhausted: nothing is claimable any more.
    worker.poll();
    assertThat(renderer.renders).hasSize(2);
  }

  @Test
  void aNonTransientFailureFailsImmediatelyNamingEachResponsibleDocument() {
    UUID externalId = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());
    FakeBundleRenderer renderer = new FakeBundleRenderer()
        .onNextRender(FakeBundleRenderer.failure(new BundleGenerationException(
            BundleErrorCode.DOCUMENT_NOT_FOUND, BundleStage.RESOLVE,
            "Two source documents do not exist.", "Correct the references and resubmit.",
            List.of(
                new DocumentFailure("doc-1", new DocumentReference("case-documents", "d1"),
                    BundleErrorCode.DOCUMENT_NOT_FOUND, "no such document"),
                new DocumentFailure("doc-2", new DocumentReference("case-documents", "d2"),
                    BundleErrorCode.DOCUMENT_NOT_FOUND, "no such document")))));
    BundleJobWorker worker = directWorker(renderer);

    worker.poll();

    BundleJob failed = job(externalId);
    assertThat(failed.state()).isEqualTo(BundleJobState.FAILED);
    assertThat(failed.attempts()).isEqualTo(1);
    BundleJobFailure failure = failed.failure().orElseThrow();
    assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_NOT_FOUND);
    assertThat(failure.message()).contains("doc-1").contains("doc-2");
    assertThat(failure.documentFailures())
        .extracting(DocumentFailure::documentId).containsExactly("doc-1", "doc-2");
    assertThat(column(externalId, "next_attempt_at")).isNull();

    // Not retried: rendering/validation failures are terminal on the first attempt.
    worker.poll();
    assertThat(renderer.renders).hasSize(1);
  }

  @Test
  void anUnreadableStoredRequestFailsClearlyWithJobRequestUnreadable() {
    UUID corrupt = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(corrupt), BundleJobFixtures.context());
    jdbc.update("update ccd_bundle_job set request = '\"not-a-request\"'::jsonb "
        + "where external_id = :id", Map.of("id", corrupt));

    UUID futureVersion = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(futureVersion), BundleJobFixtures.context());
    jdbc.update("update ccd_bundle_job set request_version = 99 where external_id = :id",
        Map.of("id", futureVersion));

    FakeBundleRenderer renderer = new FakeBundleRenderer();
    directWorker(renderer).poll();

    BundleJob corruptJob = job(corrupt);
    assertThat(corruptJob.state()).isEqualTo(BundleJobState.FAILED);
    assertThat(corruptJob.failure().orElseThrow().code())
        .isEqualTo(BundleErrorCode.JOB_REQUEST_UNREADABLE);
    assertThat(corruptJob.failure().orElseThrow().message()).contains(corrupt.toString());

    BundleJob versionJob = job(futureVersion);
    assertThat(versionJob.state()).isEqualTo(BundleJobState.FAILED);
    assertThat(versionJob.failure().orElseThrow().code())
        .isEqualTo(BundleErrorCode.JOB_REQUEST_UNREADABLE);
    assertThat(versionJob.failure().orElseThrow().message()).contains("version 99");

    // Neither job reached the renderer, and neither retries.
    assertThat(renderer.renders).isEmpty();
  }

  @Test
  void aSelectorParametersSubmissionCompilesTheRequestAtExecutionTime() {
    UUID externalId = UUID.randomUUID();
    service.submit(externalId, Map.of("bundleTitle", "Compiled at execution"),
        BundleJobFixtures.context());
    assertThat(column(externalId, "request")).isNull();
    assertThat(column(externalId, "selector_parameters")).contains("bundleTitle");

    BundleDocumentSelector selector = context -> BundleRequest.builder()
        .externalId(context.externalId())
        .title(context.parameters().get("bundleTitle"))
        .fileName("compiled.pdf")
        .root(BundleSection.builder("Case file")
            .document(uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument.builder()
                .id("doc-late")
                .title("Arrived after submission")
                .reference(new DocumentReference("case-documents", "late-1"))
                .build())
            .build())
        .build();
    FakeBundleRenderer renderer = new FakeBundleRenderer();
    BundleJobWorker worker = new BundleJobWorker(repository, renderer, selector,
        quickRetries(3), List.of(), Runnable::run, 5, 5, Duration.ofMinutes(5));

    worker.poll();

    assertThat(renderer.renders).hasSize(1);
    assertThat(renderer.renders.get(0).title()).isEqualTo("Compiled at execution");
    BundleJob completed = job(externalId);
    assertThat(completed.state()).isEqualTo(BundleJobState.COMPLETED);
    assertThat(completed.output().orElseThrow().getTitle()).isEqualTo("Compiled at execution");
  }

  @Test
  void progressEventsArriveInOrderAndAThrowingListenerNeverBreaksTheJob() {
    UUID externalId = UUID.randomUUID();
    service.submit(BundleJobFixtures.simpleRequest(externalId), BundleJobFixtures.context());
    List<BundleProgressEvent> events = new CopyOnWriteArrayList<>();
    BundleProgressListener throwing = event -> {
      throw new IllegalStateException("listener bug");
    };
    BundleJobWorker worker = directWorker(new FakeBundleRenderer(), quickRetries(3),
        List.of(throwing, events::add));

    worker.poll();

    assertThat(job(externalId).state()).isEqualTo(BundleJobState.COMPLETED);
    assertThat(events).containsExactly(
        new BundleProgressEvent(externalId, BundleJobState.RESOLVING, 0, 1),
        new BundleProgressEvent(externalId, BundleJobState.COMPLETED, 1, 1));
  }

  @Test
  void theWorkerNeverExceedsItsConcurrentRenderLimit() throws Exception {
    for (int i = 0; i < 3; i++) {
      service.submit(BundleJobFixtures.simpleRequest(UUID.randomUUID()),
          BundleJobFixtures.context());
    }
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    FakeBundleRenderer renderer = new FakeBundleRenderer();
    for (int i = 0; i < 3; i++) {
      renderer.onNextRender(request -> {
        maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          active.decrementAndGet();
        }
        return BundleJobFixtures.resultFor(request, BundleOutcome.COMPLETED);
      });
    }
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      BundleJobWorker worker = new BundleJobWorker(repository, renderer,
          BundleDocumentSelector.asSubmitted(), quickRetries(3), List.of(), pool, 5, 2,
          Duration.ofMinutes(5));

      worker.poll();
      await().atMost(Duration.ofSeconds(10)).until(() -> active.get() == 2);

      // At capacity the next poll claims nothing; the third job stays queued.
      worker.poll();
      Integer queued = jdbc.queryForObject(
          "select count(*) from ccd_bundle_job where state = 'QUEUED'", Map.of(), Integer.class);
      assertThat(queued).isEqualTo(1);

      release.countDown();
      await().atMost(Duration.ofSeconds(10)).until(() -> completedCount() == 2);
      // Polling regains capacity as the first two renders drain, and picks up the third job.
      await().atMost(Duration.ofSeconds(10)).until(() -> {
        worker.poll();
        return completedCount() == 3;
      });
      assertThat(maxActive.get()).isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }
}
