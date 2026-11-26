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
    insertCase(CASE);
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
  void aDocumentIsRenderedAttachedToTheCaseAndHandedToTheService() throws Exception {
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
    bundle.set(() -> Optional.of(request));

    event(1);
    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);

    assertThat(cdam.uploads).singleElement().satisfies(upload -> {
      assertThat(upload.fileName()).isEqualTo("hearing-bundle.pdf");
      assertThat(upload.caseTypeId()).isEqualTo("E2E");
    });
    assertThat(cdam.attachments).singleElement().satisfies(attached ->
        assertThat(attached.getCaseId()).isEqualTo(String.valueOf(CASE)));
    assertThat(cdam.attached()).containsExactly(cdam.uploads.getFirst().id());
    assertThat(handler.completed).singleElement().satisfies(call -> {
      assertThat(call.job().caseReference()).isEqualTo(CASE);
      assertThat(call.uploaded().document().getUrl())
          .isEqualTo(FakeCdam.HOST + cdam.uploads.getFirst().id());
      assertThat(call.uploaded().document().getCategoryId()).isEqualTo("bundles");
      assertThat(call.uploaded().pageCount()).isGreaterThan(2);
    });
  }

  @Test
  void aDocumentIsOnlyQueuedIfTheEventCommits() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));

    tx.executeWithoutResult(status -> {
      hook.afterCaseEvent(new CaseEventContext(CASE, "E2E", "DIVORCE", "anEvent", 1, 1));
      status.setRollbackOnly();
    });
    assertThat(jobs.status(KEY, CASE)).isEmpty();

    event(1);
    assertThat(jobs.status(KEY, CASE)).isPresent();
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
      assertThat(call.job().key()).isEqualTo(KEY);
      assertThat(call.job().revision()).isEqualTo(5);
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
  void anEventDuringTheHandlerWaitsForItThenQueuesTheNextRevision() throws Exception {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    CountDownLatch inHandler = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    handler.blockOnce(inHandler, release);

    Thread first = new Thread(worker::poll);
    first.start();
    assertThat(inHandler.await(30, TimeUnit.SECONDS)).isTrue();
    // A case event while revision 1's handler is running waits for it to commit.
    bundle.set(() -> Optional.of(bundleOf("Potential_Energy_PDF.pdf")));
    Thread second = new Thread(() -> event(2));
    second.start();
    second.join(300);
    assertThat(second.isAlive()).isTrue();
    release.countDown();
    second.join(30_000);
    first.join();

    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE
        && jobs.status(KEY, CASE).orElseThrow().revision() == 2);
    assertThat(handled()).containsExactly(1L, 2L);
  }

  @Test
  void aCaseThatNoLongerHasItsDocumentForgetsItsJobAndKeepsWhatTheServiceHas() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);

    bundle.set(Optional::empty);
    event(2);

    assertThat(jobs.status(KEY, CASE)).isEmpty();
    assertThat(handled()).containsExactly(1L);
  }

  @Test
  void aRenderWhoseCaseLostItsDocumentIsHandedToNobody() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    DocJobRepository.Claimed claimed =
        repository.claim(1, "worker-a", Duration.ofMinutes(1), 3).getFirst();
    bundle.set(Optional::empty);
    event(2);

    assertThat(repository.finish(claimed.job().id(), "worker-a", claimed.job().revision(), null))
        .isFalse();
    assertThat(jobs.status(KEY, CASE)).isEmpty();
  }

  @Test
  void aRenderSupersededBeforeItFinishesIsNeverAttached() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    DocJobRepository.Claimed claimed =
        repository.claim(1, "worker-a", Duration.ofMinutes(1), 3).getFirst();
    bundle.set(() -> Optional.of(bundleOf("Potential_Energy_PDF.pdf")));
    event(2);

    // Worker A's render finished after revision 2 arrived: it cannot finish the job.
    assertThat(repository.finish(claimed.job().id(), "worker-a", claimed.job().revision(), null))
        .isFalse();
    repository.releaseSuperseded(claimed.job().id(), "worker-a");
    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);

    assertThat(cdam.attached()).hasSize(1);
    assertThat(handled()).containsExactly(2L);
  }

  @Test
  void anAttachThatFailsRollsBackTheHandlerAndTheJobIsRenderedAgain() {
    cdam.failAttaching(new IllegalStateException("CDAM is down"));
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    worker.poll();
    await().atMost(Duration.ofSeconds(30)).until(() -> !cdam.uploads.isEmpty()
        && jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.QUEUED);
    assertThat(handled()).isEmpty();

    cdam.failAttaching(null);
    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);

    // The first upload was never attached, so it expires in CDAM.
    assertThat(cdam.uploads).hasSize(2);
    assertThat(cdam.attached()).containsExactly(cdam.uploads.getLast().id());
    assertThat(handled()).containsExactly(1L);
  }

  @Test
  void aDocumentCanBeQueuedInTheEventThatCreatesItsCase() {
    long created = CASE + 1;
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    tx.executeWithoutResult(status -> {
      hook.afterCaseEvent(new CaseEventContext(created, "E2E", "DIVORCE", "create", 1, 1));
      insertCase(created);
    });

    assertThat(jobs.status(KEY, created)).isPresent();
  }

  @Test
  void aDocumentForACaseThatDoesNotExistIsRejectedAtCommit() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> hook.afterCaseEvent(
        new CaseEventContext(CASE + 2, "E2E", "DIVORCE", "anEvent", 1, 1))))
        .rootCause().hasMessageContaining("doc_job");
  }

  @Test
  void deletingACaseDeletesItsJobs() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);

    tx.executeWithoutResult(status -> {
      jdbc.queryForObject("select set_config('ccd.audit_disabled', 'true', true)", Map.of(),
          String.class);
      jdbc.update("delete from ccd.case_data where reference = :ref", Map.of("ref", CASE));
    });

    assertThat(jdbc.queryForObject("select count(*) from bundling.doc_job", Map.of(),
        Integer.class)).isZero();
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
  void aFailingHandlerIsRolledBackAndTheJobRenderedAgain() {
    handler.failuresBeforeSuccess.set(1);
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));

    event(1);
    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.DONE);

    // Only the document the service kept is attached; the first expires in CDAM.
    assertThat(handler.completed).hasSize(2);
    assertThat(cdam.uploads).hasSize(2);
    assertThat(cdam.attached()).containsExactly(cdam.uploads.getLast().id());
    assertThat(handledUrls()).containsExactly(FakeCdam.HOST + cdam.uploads.getLast().id());
  }

  @Test
  void aHandlerThatKeepsFailingFailsTheJob() {
    handler.failuresBeforeSuccess.set(Integer.MAX_VALUE);
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);

    runUntil(() -> jobs.status(KEY, CASE).orElseThrow().state() == DocJobState.FAILED);

    assertThat(jobs.status(KEY, CASE).orElseThrow().failure()).hasValueSatisfying(failure -> {
      assertThat(failure.reason()).isEqualTo("HANDLER_FAILED");
      assertThat(failure.message()).contains("the service's database is down");
    });
    assertThat(handled()).isEmpty();
    assertThat(cdam.attached()).isEmpty();
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
    assertThat(handler.completed).isEmpty();
    assertThat(cdam.uploads).isEmpty();
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
    assertThat(handled()).containsExactly(2L);
  }

  @Test
  void aJobWhoseWorkersKeepDyingIsFailed() {
    bundle.set(() -> Optional.of(bundleOf("one-page.pdf")));
    event(1);
    // Three workers claim it and die: their leases run out without a result.
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThat(repository.claim(1, "dead-worker-" + attempt, Duration.ofMillis(1), 3))
          .hasSize(1);
      await().pollDelay(Duration.ofMillis(5)).until(() -> true);
    }

    worker.poll();

    CaseDocStatus status = jobs.status(KEY, CASE).orElseThrow();
    assertThat(status.state()).isEqualTo(DocJobState.FAILED);
    assertThat(status.failure().orElseThrow().reason()).isEqualTo("CRASHED");
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

    bundle.set(() -> Optional.of(
        PdfRequest.builder("Letter").document(DocSource.file(letter)).build()));

    assertThatThrownBy(() -> event(1))
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

  private void insertCase(long reference) {
    jdbc.update("""
        insert into ccd.case_data
          (id, reference, case_revision, security_classification, jurisdiction, case_type_id,
           state, data)
        values (:ref, :ref, 1, 'PUBLIC', 'DIVORCE', 'E2E', 'Submitted', '{}'::jsonb)
        on conflict (reference) do nothing
        """, Map.of("ref", reference));
  }

  private List<String> handledUrls() {
    return jdbc.queryForList("select url from handled order by revision", Map.of(), String.class);
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

  /** Records calls, and writes each version to a table in the worker's transaction. */
  private final class RecordingHandler implements DocJobHandler {
    final List<Completed> completed = new CopyOnWriteArrayList<>();
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
      jdbc.update("insert into handled (revision, url) values (:revision, :url)",
          Map.of("revision", job.revision(), "url", uploaded.document().getUrl()));
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
  }
}
