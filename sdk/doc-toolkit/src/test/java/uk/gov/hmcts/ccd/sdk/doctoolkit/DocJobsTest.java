package uk.gov.hmcts.ccd.sdk.doctoolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.FakeCdam;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.OutboxDatabase;

class DocJobsTest {
  private static final long CASE = 1234567890123456L;
  private static final String KEY = "case-bundle";
  private static final DocJobWorker.Settings QUICK = new DocJobWorker.Settings(
      Duration.ofHours(1), 2, Duration.ofMinutes(1), 3, Duration.ofMillis(1),
      Duration.ofMillis(1));

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
  private final Map<String, UUID> inCdam = new java.util.concurrent.ConcurrentHashMap<>();
  private DocToolkit toolkit;
  private DocJobWorker worker;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from bundling.doc_job", Map.of());
    insertCase(CASE);
    jdbc.update("drop table if exists handled", Map.of());
    jdbc.update("create table handled (n serial, file text, url text, case_revision bigint)",
        Map.of());
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
    submit(request);
    runUntil(() -> state().equals("DONE"));

    assertThat(cdam.uploads).singleElement().satisfies(upload -> {
      assertThat(upload.fileName()).isEqualTo("hearing-bundle.pdf");
      assertThat(upload.caseTypeId()).isEqualTo("E2E");
    });
    assertThat(cdam.attachments).singleElement().satisfies(attached ->
        assertThat(attached.getCaseId()).isEqualTo(String.valueOf(CASE)));
    assertThat(cdam.attached()).containsExactly(cdam.uploads.getFirst().id());
    assertThat(handler.completed).singleElement().satisfies(call -> {
      assertThat(call.job().caseReference()).isEqualTo(CASE);
      assertThat(call.job().key()).isEqualTo(KEY);
      assertThat(call.uploaded().document().getUrl())
          .isEqualTo(FakeCdam.HOST + cdam.uploads.getFirst().id());
      assertThat(call.uploaded().document().getCategoryId()).isEqualTo("bundles");
      assertThat(call.uploaded().pageCount()).isGreaterThan(2);
    });
  }

  @Test
  void aDocumentIsOnlyQueuedIfTheEventCommits() {
    tx.executeWithoutResult(status -> {
      jobs.submit(CASE, KEY, bundleOf("one-page.pdf"));
      status.setRollbackOnly();
    });
    assertThat(job()).isEmpty();

    submit(bundleOf("one-page.pdf"));
    assertThat(job()).isPresent();
  }

  @Test
  void anUnchangedRequestQueuesNothing() {
    submit(bundleOf("one-page.pdf"));
    runUntil(() -> state().equals("DONE"));

    submit(bundleOf("one-page.pdf"));
    submit(bundleOf("one-page.pdf"));

    assertThat(state()).isEqualTo("DONE");
    worker.poll();
    assertThat(cdam.uploads).hasSize(1);
  }

  @Test
  void theServiceIsGivenTheCaseRevisionTheChangedRequestWasSubmittedAt() {
    long first = nextCaseRevision();
    submit(bundleOf("one-page.pdf"));
    runUntil(() -> state().equals("DONE"));
    // A later event leaves the request as it is: the version still shows the case as it was.
    nextCaseRevision();
    submit(bundleOf("one-page.pdf"));
    long third = nextCaseRevision();
    submit(bundleOf("Potential_Energy_PDF.pdf"));
    runUntil(() -> state().equals("DONE"));

    assertThat(handledRevisions()).containsExactly(first, third);
  }

  @Test
  void aNewerRequestReplacesOneNotYetRendered() {
    submit(bundleOf("one-page.pdf"));
    submit(bundleOf("Potential_Energy_PDF.pdf"));

    runUntil(() -> state().equals("DONE"));

    assertThat(handled()).containsExactly("Potential_Energy_PDF.pdf");
  }

  @Test
  void aSubmitDuringTheHandlerWaitsForItThenQueuesTheNextVersion() throws Exception {
    submit(bundleOf("one-page.pdf"));
    CountDownLatch inHandler = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    handler.blockOnce(inHandler, release);

    Thread first = new Thread(worker::poll);
    first.start();
    assertThat(inHandler.await(30, TimeUnit.SECONDS)).isTrue();
    // A submit while the first version's handler is running waits for it to commit.
    Thread second = new Thread(() -> submit(bundleOf("Potential_Energy_PDF.pdf")));
    second.start();
    second.join(300);
    assertThat(second.isAlive()).isTrue();
    release.countDown();
    second.join(30_000);
    first.join();

    runUntil(() -> handled().size() == 2);
    assertThat(handled()).containsExactly("one-page.pdf", "Potential_Energy_PDF.pdf");
  }

  @Test
  void aRenderSupersededBeforeItFinishesIsNeverAttached() {
    submit(bundleOf("one-page.pdf"));
    DocJobRepository.Claimed claimed =
        repository.claim(1, "worker-a", Duration.ofMinutes(1), 3).getFirst();
    submit(bundleOf("Potential_Energy_PDF.pdf"));

    // Worker A's render finished after a newer request arrived: it cannot finish the job.
    assertThat(repository.finish(claimed.job().id(), "worker-a", claimed.revision())).isFalse();
    repository.releaseSuperseded(claimed.job().id(), "worker-a");
    runUntil(() -> state().equals("DONE"));

    assertThat(cdam.attached()).hasSize(1);
    assertThat(handled()).containsExactly("Potential_Energy_PDF.pdf");
  }

  @Test
  void anAttachThatFailsRollsBackTheHandlerAndTheJobIsRenderedAgain() {
    cdam.failAttaching(new IllegalStateException("CDAM is down"));
    submit(bundleOf("one-page.pdf"));
    worker.poll();
    await().atMost(Duration.ofSeconds(30)).until(() -> !cdam.uploads.isEmpty()
        && state().equals("QUEUED"));
    assertThat(handled()).isEmpty();

    cdam.failAttaching(null);
    runUntil(() -> state().equals("DONE"));

    // The first upload was never attached, so it expires in CDAM.
    assertThat(cdam.uploads).hasSize(2);
    assertThat(cdam.attached()).containsExactly(cdam.uploads.getLast().id());
    assertThat(handled()).containsExactly("one-page.pdf");
  }

  @Test
  void aDocumentCanBeQueuedInTheEventThatCreatesItsCase() {
    long created = CASE + 1;
    tx.executeWithoutResult(status -> {
      insertCase(created);
      jobs.submit(created, KEY, bundleOf("one-page.pdf"));
    });

    assertThat(jdbc.queryForObject("select count(*) from bundling.doc_job"
        + " where case_reference = :ref", Map.of("ref", created), Integer.class)).isOne();
  }

  @Test
  void aDocumentForACaseThatDoesNotExistIsRejected() {
    assertThatThrownBy(() -> tx.executeWithoutResult(status ->
        jobs.submit(CASE + 2, KEY, bundleOf("one-page.pdf"))))
        .rootCause().hasMessageContaining("case_revision");
  }

  @Test
  void deletingACaseDeletesItsJobs() {
    submit(bundleOf("one-page.pdf"));

    tx.executeWithoutResult(status -> {
      jdbc.queryForObject("select set_config('ccd.audit_disabled', 'true', true)", Map.of(),
          String.class);
      jdbc.update("delete from ccd.case_data where reference = :ref", Map.of("ref", CASE));
    });

    assertThat(jdbc.queryForObject("select count(*) from bundling.doc_job", Map.of(),
        Integer.class)).isZero();
  }

  @Test
  void anUnavailableDocumentIsRetriedThenFailsTheJobWithoutAPlaceholder() {
    submit(bundleOf("one-page.pdf"));
    runUntil(() -> state().equals("DONE"));

    UUID outage = cdam.failing(503);
    submit(PdfRequest.builder("Bundle")
        .document(PdfPart.builder(DocSource.cdam(outage)).id("doc-1").title("Outage").build())
        .tableOfContents()
        .missingDocuments(MissingDocuments.PLACEHOLDER)
        .build());
    runUntil(() -> state().equals("FAILED"));

    assertThat(job().orElseThrow()).satisfies(job -> {
      assertThat(job.attempts()).isEqualTo(3);
      assertThat(job.failureDocuments()).contains("doc-1", "Outage", "UNAVAILABLE");
    });
    // The service keeps the version it had; nothing with a placeholder was published.
    assertThat(handled()).containsExactly("one-page.pdf");
    assertThat(cdam.uploads).hasSize(1);
  }

  @Test
  void theSameRequestSubmittedAfterAFailureIsTriedAgain() {
    UUID outage = cdam.failing(503);
    PdfRequest request = PdfRequest.builder("Bundle").fileName("outage.pdf")
        .document(PdfPart.builder(DocSource.cdam(outage)).title("Outage").build())
        .build();
    submit(request);
    runUntil(() -> state().equals("FAILED"));

    cdam.recover(outage, fixture("one-page.pdf"));
    submit(request);
    runUntil(() -> state().equals("DONE"));

    assertThat(handled()).containsExactly("outage.pdf");
  }

  @Test
  void aFailingHandlerIsRolledBackAndTheJobRenderedAgain() {
    handler.failuresBeforeSuccess.set(1);

    submit(bundleOf("one-page.pdf"));
    runUntil(() -> state().equals("DONE"));

    // Only the document the service kept is attached; the first expires in CDAM.
    assertThat(handler.completed).hasSize(2);
    assertThat(cdam.uploads).hasSize(2);
    assertThat(cdam.attached()).containsExactly(cdam.uploads.getLast().id());
    assertThat(handledUrls()).containsExactly(FakeCdam.HOST + cdam.uploads.getLast().id());
  }

  @Test
  void aHandlerThatKeepsFailingFailsTheJob() {
    handler.failuresBeforeSuccess.set(Integer.MAX_VALUE);
    submit(bundleOf("one-page.pdf"));

    runUntil(() -> state().equals("FAILED"));

    assertThat(job().orElseThrow()).satisfies(job -> {
      assertThat(job.failureReason()).isEqualTo("HANDLER_FAILED");
      assertThat(job.failureMessage()).contains("the service's database is down");
    });
    assertThat(handled()).isEmpty();
    assertThat(cdam.attached()).isEmpty();
  }

  @Test
  void aPermanentFailureFailsTheJobWithoutRetrying() {
    submit(PdfRequest.builder("Bundle")
        .document(PdfPart.builder(DocSource.cdam(cdam.failing(404))).title("Gone").build())
        .build());

    runUntil(() -> state().equals("FAILED"));

    assertThat(job().orElseThrow()).satisfies(job -> {
      assertThat(job.attempts()).isEqualTo(1);
      assertThat(job.failureReason()).isEqualTo("DOCUMENTS_FAILED");
      assertThat(job.failureDocuments()).contains("Gone", "NOT_FOUND");
    });
    assertThat(handler.completed).isEmpty();
    assertThat(cdam.uploads).isEmpty();
  }

  @Test
  void aFailureOfASupersededRenderIsNotReported() {
    submit(bundleOf("one-page.pdf"));
    DocJobRepository.Claimed claimed =
        repository.claim(1, "worker-a", Duration.ofMinutes(1), 3).getFirst();
    submit(bundleOf("Potential_Energy_PDF.pdf"));

    assertThat(repository.markFailed(claimed.job().id(), "worker-a", claimed.revision(),
        "DOCUMENTS_FAILED", "lost", "[]")).isFalse();
    assertThat(repository.releaseSuperseded(claimed.job().id(), "worker-a")).isTrue();

    runUntil(() -> state().equals("DONE"));
    assertThat(handled()).containsExactly("Potential_Energy_PDF.pdf");
  }

  @Test
  void aJobWhoseWorkersKeepDyingIsFailed() {
    submit(bundleOf("one-page.pdf"));
    // Three workers claim it and die: their leases run out without a result.
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThat(repository.claim(1, "dead-worker-" + attempt, Duration.ofMillis(1), 3))
          .hasSize(1);
      await().pollDelay(Duration.ofMillis(5)).until(() -> true);
    }

    worker.poll();

    assertThat(state()).isEqualTo("FAILED");
    assertThat(job().orElseThrow().failureReason()).isEqualTo("CRASHED");
  }

  @Test
  void aStoredRequestThatCannotBeReadFailsTheJob() {
    submit(bundleOf("one-page.pdf"));
    jdbc.update("update bundling.doc_job set request = '{\"title\":\"x\"}'::jsonb", Map.of());

    runUntil(() -> state().equals("FAILED"));

    assertThat(job().orElseThrow().failureReason()).isEqualTo("UNREADABLE_REQUEST");
  }

  @Test
  void aLocalFileCannotBeQueued() throws Exception {
    Path letter = Files.write(work.resolve("letter.pdf"), fixture("one-page.pdf"));

    PdfRequest request = PdfRequest.builder("Letter").document(DocSource.file(letter)).build();

    assertThatThrownBy(() -> submit(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DocSource.bytes");
  }

  @Test
  void anOversizedRequestFailsTheEvent() {
    byte[] large = new byte[DocJobs.MAX_REQUEST_BYTES];
    PdfRequest request = PdfRequest.builder("Bundle")
        .document(DocSource.bytes(large, "large.pdf")).build();

    assertThatThrownBy(() -> submit(request)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CDAM");
  }

  /** Submits as a case event would, in its own transaction. */
  private void submit(PdfRequest request) {
    tx.executeWithoutResult(status -> jobs.submit(CASE, KEY, request));
  }

  private record JobRow(String state, int attempts, String failureReason, String failureMessage,
      String failureDocuments) {
  }

  private Optional<JobRow> job() {
    return jdbc.query("""
        select state, attempts, failure_reason, failure_message,
               failure_documents::text as failure_documents
        from bundling.doc_job where case_reference = :ref and job_key = :key
        """, Map.of("ref", CASE, "key", KEY), (rs, n) -> new JobRow(rs.getString("state"),
        rs.getInt("attempts"), rs.getString("failure_reason"), rs.getString("failure_message"),
        rs.getString("failure_documents"))).stream().findFirst();
  }

  private String state() {
    return job().map(JobRow::state).orElse("NONE");
  }

  private PdfRequest bundleOf(String fixture) {
    // Stored once, so the same fixture is the same CDAM document, as a case's documents are.
    UUID id = inCdam.computeIfAbsent(fixture,
        name -> cdam.store(fixture(name), "application/pdf", name));
    return PdfRequest.builder("Bundle")
        .fileName(fixture)
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
    return jdbc.queryForList("select url from handled order by n", Map.of(), String.class);
  }

  private List<Long> handledRevisions() {
    return jdbc.queryForList("select case_revision from handled order by n", Map.of(), Long.class);
  }

  /** Moves the case on a revision, as an event does, returning the new one. */
  private long nextCaseRevision() {
    return jdbc.queryForObject("update ccd.case_data set case_revision = case_revision + 1"
        + " where reference = :ref returning case_revision", Map.of("ref", CASE), Long.class);
  }

  /** The file names of the versions the service kept, in order. */
  private List<String> handled() {
    return jdbc.queryForList("select file from handled order by n", Map.of(), String.class);
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
      jdbc.update("insert into handled (file, url, case_revision) values (:file, :url, :revision)",
          Map.of("file", uploaded.document().getFilename(), "url", uploaded.document().getUrl(),
              "revision", job.caseRevision()));
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
