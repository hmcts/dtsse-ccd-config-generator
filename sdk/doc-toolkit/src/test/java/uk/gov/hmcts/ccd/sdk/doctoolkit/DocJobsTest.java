package uk.gov.hmcts.ccd.sdk.doctoolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.ONE_CASE_TYPE;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.SERVICE_TOKENS;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.SYSTEM_USER;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.CaseEventContext;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.FakeCdam;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.OutboxDatabase;

class DocJobsTest {
  private static final long CASE = 1234567890123456L;
  private static final String KEY = "case-bundle";
  private static final DocJobWorker.Settings QUICK = new DocJobWorker.Settings(
      Duration.ofHours(1), 2, Duration.ofMinutes(1), 3, Duration.ofMillis(1),
      Duration.ofMillis(1), Duration.ofMillis(1), 2);

  @TempDir
  private Path work;

  private final NamedParameterJdbcTemplate jdbc =
      new NamedParameterJdbcTemplate(OutboxDatabase.dataSource());
  private final TransactionTemplate tx =
      new TransactionTemplate(new DataSourceTransactionManager(OutboxDatabase.dataSource()));
  private final DocJobRepository repository = new DocJobRepository(jdbc);
  private final DocJobs jobs = new DocJobs(repository);
  private final FakeCdam cdam = new FakeCdam();
  private final RecordingHandler handler = new RecordingHandler();
  private final AtomicReference<Supplier<Optional<PdfRequest>>> bundle =
      new AtomicReference<>(Optional::empty);
  private final CaseDocHook hook = new CaseDocHook(List.of(new CaseDocDefinition() {
    @Override
    public String key() {
      return KEY;
    }

    @Override
    public Set<String> caseTypes() {
      return Set.of("E2E");
    }

    @Override
    public Optional<PdfRequest> build(long caseReference) {
      return bundle.get().get();
    }
  }), repository);
  private final Map<String, UUID> inCdam = new java.util.concurrent.ConcurrentHashMap<>();
  private DocToolkit toolkit;
  private DocJobWorker worker;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from bundling.doc_job", Map.of());
    jdbc.update("create table if not exists handled (revision bigint, url text)", Map.of());
    jdbc.update("delete from handled", Map.of());
    toolkit = DocToolkit.builder().tempDirectory(work)
        .cdam(cdam, SERVICE_TOKENS, SYSTEM_USER, ONE_CASE_TYPE).build();
    worker = new DocJobWorker(repository, toolkit, handler, tx, QUICK);
  }

  @AfterEach
  void tearDown() {
    worker.close();
  }

  @Test
  void aOneOffJobIsStoredInCdamHandedToTheServiceAndDeleted() throws Exception {
    UUID claim = cdam.store(fixture("one-page.pdf"), "application/pdf", "claim.pdf");
    Path draft = Files.write(work.resolve("draft.png"), fixture("schmcts.png"));
    PdfRequest request = PdfRequest.builder("Hearing bundle")
        .fileName("hearing-bundle.pdf")
        .section(PdfSection.builder("Claim")
            .document(PdfPart.builder(DocSource.cdam(claim)).title("Claim form").build())
            .document(HtmlPage.builder().title("Recording").body("<p>Held by the court</p>").build())
            .build())
        .tableOfContents()
        .watermark(Watermark.image(draft))
        .category("bundles")
        .build();

    DocJob queued = jobs.submit(request, CASE);
    assertThat(queued.state()).isEqualTo(DocJobState.QUEUED);
    assertThat(queued.key()).isEmpty();
    assertThat(queued.revision()).isEmpty();

    runUntil(() -> jobs.find(queued.id()).isEmpty());

    assertThat(cdam.uploads).singleElement().satisfies(upload -> {
      assertThat(upload.fileName()).isEqualTo("hearing-bundle.pdf");
      assertThat(upload.caseTypeId()).isEqualTo("E2E");
    });
    assertThat(cdam.attachments).singleElement().satisfies(attached ->
        assertThat(attached.getCaseId()).isEqualTo(String.valueOf(CASE)));
    assertThat(handler.completed).singleElement().satisfies(call -> {
      assertThat(call.job().id()).isEqualTo(queued.id());
      assertThat(call.job().caseReference()).isEqualTo(CASE);
      assertThat(call.uploaded().document().getUrl())
          .isEqualTo(FakeCdam.HOST + cdam.uploads.getFirst().id());
      assertThat(call.uploaded().document().getCategoryId()).isEqualTo("bundles");
      assertThat(call.uploaded().pageCount()).isGreaterThan(2);
    });
  }

  @Test
  void aJobIsOnlyQueuedIfTheTransactionQueuingItCommits() {
    PdfRequest request = letter();

    UUID rolledBack = tx.execute(status -> {
      UUID id = jobs.submit(request, CASE).id();
      status.setRollbackOnly();
      return id;
    });
    UUID committed = tx.execute(status -> jobs.submit(request, CASE).id());

    assertThat(jobs.find(rolledBack)).isEmpty();
    assertThat(jobs.find(committed)).isPresent();
  }

  @Test
  void aCaseEventQueuesItsDocumentAndTheServiceGetsItsKeyAndRevision() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));

    event(5);
    assertThat(jobs.status(KEY, CASE)).hasValueSatisfying(status -> {
      assertThat(status.state()).isEqualTo(DocJobState.QUEUED);
      assertThat(status.revision()).isEqualTo(5);
    });
    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);

    assertThat(handler.completed).singleElement().satisfies(call -> {
      assertThat(call.job().key()).contains(KEY);
      assertThat(call.job().revision()).hasValue(5);
    });
    assertThat(handled()).containsExactly(5L);
  }

  @Test
  void anEventThatLeavesTheRequestUnchangedQueuesNothing() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(5);
    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);

    event(6);
    event(7);

    assertThat(jobs.status(KEY, CASE)).hasValueSatisfying(status -> {
      assertThat(status.state()).isEqualTo(DocJobState.DONE);
      assertThat(status.revision()).isEqualTo(5);
    });
    worker.poll();
    assertThat(cdam.uploads).hasSize(1);
  }

  @Test
  void anOlderRevisionNeverReplacesANewerOne() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(9);
    bundle.set(() -> Optional.of(bundleOf("Potential_Energy_PDF.pdf")));

    event(8);

    assertThat(jobs.status(KEY, CASE).orElseThrow().revision()).isEqualTo(9);
  }

  @Test
  void aChangeWhileRenderingSupersedesTheRenderAndItsResultIsDiscarded() throws Exception {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    CountDownLatch inHandler = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    handler.blockOnce(inHandler, release);

    Thread first = new Thread(worker::poll);
    first.start();
    assertThat(inHandler.await(30, TimeUnit.SECONDS)).isTrue();
    // A case event while revision 1's handler is running.
    bundle.set(() -> Optional.of(bundleOf("Potential_Energy_PDF.pdf")));
    event(2);
    release.countDown();
    first.join();
    await().atMost(Duration.ofSeconds(30)).until(() -> handler.completed.size() >= 1
        && jobs.status(KEY, CASE).orElseThrow().state() != DocJobState.IN_PROGRESS);

    // Revision 1's handler ran but its transaction rolled back, and its upload was deleted.
    assertThat(handled()).isEmpty();
    UUID firstUpload = cdam.uploads.getFirst().id();
    assertThat(cdam.deleted).containsExactly(firstUpload);

    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);
    assertThat(handled()).containsExactly(2L);
    assertThat(jobs.status(KEY, CASE).orElseThrow().revision()).isEqualTo(2);
  }

  @Test
  void aCaseThatNoLongerHasItsDocumentHasItRemoved() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);

    bundle.set(Optional::empty);
    event(2);
    runUntil(() -> jobs.status(KEY, CASE).isEmpty());

    assertThat(handler.removed).singleElement().satisfies(job -> {
      assertThat(job.key()).contains(KEY);
      assertThat(job.revision()).hasValue(2);
    });
    assertThat(cdam.deleted).isEmpty();
  }

  @Test
  void aCaseThatNeverHadTheDocumentQueuesNothing() {
    event(1);

    assertThat(jobs.status(KEY, CASE)).isEmpty();
  }

  @Test
  void anUnavailableDocumentIsRetriedThenPlacedAndTheBundleIsRenderedAgainLater() {
    UUID outage = cdam.failing(503);
    bundle.set(() -> Optional.of(PdfRequest.builder("Bundle")
        .document(PdfPart.builder(DocSource.cdam(outage)).id("doc-1").title("Outage").build())
        .tableOfContents()
        .missingDocuments(MissingDocuments.PLACEHOLDER)
        .build()));
    event(1);

    // Three attempts, the last with a placeholder; then two later re-renders, also placeholders.
    runUntil(() -> handler.completed.size() == 3
        && jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);

    assertThat(handler.completed.getFirst().uploaded().failures())
        .extracting(DocFailure::id, DocFailure::title, DocFailure::reason)
        .containsExactly(tuple(Optional.of("doc-1"), "Outage", DocFailure.Reason.UNAVAILABLE));
    assertThat(cdam.uploads).hasSize(3);
    worker.poll();
    assertThat(handler.completed).hasSize(3);
  }

  @Test
  void aFailingHandlerIsCalledAgainWithTheSameDocument() {
    handler.failuresBeforeSuccess.set(1);

    UUID id = jobs.submit(letter(), CASE).id();
    runUntil(() -> jobs.find(id).isEmpty());

    assertThat(cdam.uploads).hasSize(1);
    assertThat(handler.completed).hasSize(2)
        .extracting(call -> call.uploaded().document().getUrl())
        .containsOnly(FakeCdam.HOST + cdam.uploads.getFirst().id());
  }

  @Test
  void aHandlerThatKeepsFailingFailsTheJob() {
    handler.failuresBeforeSuccess.set(Integer.MAX_VALUE);
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);

    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.FAILED);

    assertThat(jobs.status(KEY, CASE).orElseThrow().failure()).hasValueSatisfying(failure -> {
      assertThat(failure.reason()).isEqualTo("HANDLER_FAILED");
      assertThat(failure.message()).contains(FakeCdam.HOST);
    });
    assertThat(handler.failed).singleElement()
        .satisfies(job -> assertThat(job.revision()).hasValue(1));
  }

  @Test
  void aPermanentFailureFailsTheJobAndTellsTheService() {
    bundle.set(() -> Optional.of(PdfRequest.builder("Bundle")
        .document(PdfPart.builder(DocSource.cdam(cdam.failing(404))).title("Gone").build())
        .build()));
    event(1);

    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.FAILED);

    CaseDocStatus status = jobs.status(KEY, CASE).orElseThrow();
    assertThat(status.attempts()).isEqualTo(1);
    assertThat(status.failure()).hasValueSatisfying(failure -> {
      assertThat(failure.reason()).isEqualTo("DOCUMENTS_FAILED");
      assertThat(failure.documents()).extracting(DocFailure::title, DocFailure::reason)
          .containsExactly(tuple("Gone", DocFailure.Reason.NOT_FOUND));
    });
    assertThat(handler.failed).hasSize(1);
    assertThat(handler.completed).isEmpty();
    assertThat(cdam.uploads).isEmpty();
  }

  @Test
  void aFailedOneOffJobIsDeletedOnceTheServiceIsTold() {
    UUID id = jobs.submit(PdfRequest.builder("Letter")
        .document(DocSource.cdam(cdam.failing(404))).build(), CASE).id();

    runUntil(() -> jobs.find(id).isEmpty());

    assertThat(handler.failed).extracting(DocJob::id).containsExactly(id);
  }

  @Test
  void aFailureOfASupersededRenderIsNotReported() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    DocJobRepository.Claimed claimed =
        repository.claim(1, "worker-a", Duration.ofMinutes(1), 3).getFirst();
    bundle.set(() -> Optional.of(bundleOf("Potential_Energy_PDF.pdf")));
    event(2);

    assertThat(repository.markFailed(claimed.job().id(), "worker-a", claimed.job().revision(),
        "DOCUMENTS_FAILED", "lost", "[]")).isFalse();
    assertThat(repository.releaseSuperseded(claimed.job().id(), "worker-a")).isTrue();

    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);
    assertThat(handler.failed).isEmpty();
    assertThat(handled()).containsExactly(2L);
  }

  @Test
  void aJobWhoseWorkersKeepDyingIsFailed() {
    UUID id = jobs.submit(letter(), CASE).id();
    // Three workers claim it and die: their leases run out without a result.
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThat(repository.claim(1, "dead-worker-" + attempt, Duration.ofMillis(1), 3))
          .hasSize(1);
      await().pollDelay(Duration.ofMillis(5)).until(() -> true);
    }

    worker.poll();

    assertThat(handler.failed).singleElement().satisfies(job -> {
      assertThat(job.id()).isEqualTo(id);
      assertThat(job.failure().orElseThrow().reason()).isEqualTo("CRASHED");
    });
    assertThat(jobs.find(id)).isEmpty();
  }

  @Test
  void aStoredRequestThatCannotBeReadFailsTheJob() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    jdbc.update("update bundling.doc_job set request = '{\"title\":\"x\"}'::jsonb", Map.of());

    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.FAILED);

    assertThat(jobs.status(KEY, CASE).orElseThrow().failure().orElseThrow().reason())
        .isEqualTo("UNREADABLE_REQUEST");
  }

  @Test
  void aLocalFileCannotBeQueued() throws Exception {
    Path letter = Files.write(work.resolve("letter.pdf"), fixture("one-page.pdf"));

    assertThatThrownBy(() -> jobs.submit(
        PdfRequest.builder("Letter").document(DocSource.file(letter)).build(), CASE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DocSource.bytes");
  }

  @Test
  void anOversizedRequestFailsTheEvent() {
    byte[] large = new byte[CaseDocHook.MAX_REQUEST_BYTES];
    bundle.set(() -> Optional.of(PdfRequest.builder("Bundle")
        .document(DocSource.bytes(large, "large.pdf")).build()));

    assertThatThrownBy(() -> event(1)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CDAM");
  }

  private void event(long revision) {
    tx.executeWithoutResult(status ->
        hook.afterCaseEvent(new CaseEventContext(CASE, "E2E", "DIVORCE", "anEvent", revision,
            revision)));
  }

  private PdfRequest bundleOf(String fixture) {
    // Stored once, so the same fixture is the same CDAM document, as a case's documents are.
    UUID id = inCdam.computeIfAbsent(fixture,
        name -> cdam.store(fixture(name), "application/pdf", name));
    return PdfRequest.builder("Bundle")
        .section(PdfSection.builder("Claim")
            .document(PdfPart.builder(DocSource.cdam(id)).title(fixture).build()).build())
        .titlePage()
        .tableOfContents()
        .build();
  }

  private static PdfRequest letter() {
    return PdfRequest.builder("Letter")
        .document(DocSource.bytes(fixture("one-page.pdf"), "letter.pdf")).build();
  }

  private List<Long> handled() {
    return jdbc.queryForList("select revision from handled order by revision", Map.of(),
        Long.class);
  }

  private void runUntil(java.util.concurrent.Callable<Boolean> done) {
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(20)).until(() -> {
      worker.poll();
      return done.call();
    });
  }

  private record Completed(DocJob job, UploadedDoc uploaded) {
  }

  /** Records calls, and writes completed keyed jobs to a table in the worker's transaction. */
  private final class RecordingHandler implements DocJobHandler {
    final List<Completed> completed = new CopyOnWriteArrayList<>();
    final List<DocJob> failed = new CopyOnWriteArrayList<>();
    final List<DocJob> removed = new CopyOnWriteArrayList<>();
    final AtomicInteger failuresBeforeSuccess = new AtomicInteger();
    private volatile CountDownLatch blockEntered;
    private volatile CountDownLatch blockRelease;

    void blockOnce(CountDownLatch entered, CountDownLatch release) {
      this.blockEntered = entered;
      this.blockRelease = release;
    }

    @Override
    public void completed(DocJob job, UploadedDoc uploaded) throws Exception {
      completed.add(new Completed(job, uploaded));
      if (job.revision().isPresent()) {
        jdbc.update("insert into handled (revision, url) values (:revision, :url)",
            Map.of("revision", job.revision().getAsLong(),
                "url", uploaded.document().getUrl()));
      }
      CountDownLatch entered = blockEntered;
      if (entered != null) {
        blockEntered = null;
        entered.countDown();
        blockRelease.await(30, TimeUnit.SECONDS);
      }
      if (failuresBeforeSuccess.getAndDecrement() > 0) {
        throw new IllegalStateException("the service's database is down");
      }
    }

    @Override
    public void failed(DocJob job) {
      failed.add(job);
    }

    @Override
    public void removed(DocJob job) {
      removed.add(job);
    }
  }
}
