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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
  private static final long CASE_ID = 1234567890123456L;
  private static final DocJobWorker.Settings QUICK = new DocJobWorker.Settings(
      Duration.ofHours(1), 2, Duration.ofMinutes(1), 3, Duration.ofMillis(1), Duration.ofMillis(1));

  @TempDir
  private Path work;

  private final NamedParameterJdbcTemplate jdbc =
      new NamedParameterJdbcTemplate(OutboxDatabase.dataSource());
  private final DocJobRepository repository = new DocJobRepository(jdbc);
  private final DocJobs jobs = new DocJobs(repository);
  private final FakeCdam cdam = new FakeCdam();
  private final RecordingHandler handler = new RecordingHandler();
  private DocJobWorker worker;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from bundling.bundle_job", Map.of());
    DocToolkit toolkit = DocToolkit.builder().tempDirectory(work)
        .cdam(cdam, SERVICE_TOKENS, SYSTEM_USER, ONE_CASE_TYPE).build();
    worker = new DocJobWorker(repository, toolkit, handler, QUICK);
  }

  @AfterEach
  void tearDown() {
    worker.close();
  }

  @Test
  void aQueuedBundleIsStoredInCdamAndHandedToTheService() throws Exception {
    UUID claim = cdam.store(fixture("one-page.pdf"), "application/pdf", "claim.pdf");
    Path draft = Files.write(work.resolve("draft.png"), fixture("schmcts.png"));
    BundleRequest request = BundleRequest.builder("Hearing bundle")
        .fileName("hearing-bundle.pdf")
        .section(BundleSection.builder("Claim")
            .document(BundleDocument.builder(DocSource.cdam(claim)).title("Claim form").build())
            .document(HtmlPage.builder().title("Recording").body("<p>Held by the court</p>").build())
            .build())
        .watermark(Watermark.image(draft))
        .build();

    DocJob queued = jobs.submit(request, CASE_ID, "createBundle");
    assertThat(queued.state()).isEqualTo(DocJobState.QUEUED);

    DocJob done = runToEnd(queued.id());
    assertThat(done.state()).isEqualTo(DocJobState.COMPLETED);
    assertThat(cdam.uploads).singleElement().satisfies(upload -> {
      assertThat(upload.fileName()).isEqualTo("hearing-bundle.pdf");
      assertThat(upload.caseTypeId()).isEqualTo("E2E");
    });
    assertThat(cdam.attachments).singleElement().satisfies(attached ->
        assertThat(attached.getCaseId()).isEqualTo(String.valueOf(CASE_ID)));
    assertThat(handler.completed).singleElement().satisfies(call -> {
      assertThat(call.job().caseId()).isEqualTo(CASE_ID);
      assertThat(call.job().eventId()).isEqualTo("createBundle");
      assertThat(call.request()).isInstanceOfSatisfying(BundleRequest.class, stored -> {
        assertThat(stored.title()).isEqualTo("Hearing bundle");
        assertThat(stored.allDocuments()).extracting(BundleDocument::title)
            .containsExactly("Claim form", "Recording");
        assertThat(stored.watermark()).contains(Watermark.image(draft));
      });
      assertThat(call.uploaded().document().getUrl())
          .isEqualTo(FakeCdam.HOST + cdam.uploads.getFirst().id());
    });
    assertThat(done.result()).hasValueSatisfying(result -> {
      assertThat(result.document().getUrl()).isEqualTo(FakeCdam.HOST + cdam.uploads.getFirst().id());
      assertThat(result.pageCount()).isGreaterThan(2);
    });
  }

  @Test
  void aJobIsOnlyQueuedIfTheTransactionQueuingItCommits() {
    TransactionTemplate tx =
        new TransactionTemplate(new DataSourceTransactionManager(OutboxDatabase.dataSource()));
    DocConversionRequest request =
        DocConversionRequest.of(DocSource.bytes(fixture("one-page.pdf"), "letter.pdf"));

    UUID rolledBack = tx.execute(status -> {
      UUID id = jobs.submit(request, CASE_ID, "sendLetter").id();
      status.setRollbackOnly();
      return id;
    });
    UUID committed = tx.execute(status -> jobs.submit(request, CASE_ID, "sendLetter").id());

    assertThat(jobs.find(rolledBack)).isEmpty();
    assertThat(jobs.find(committed)).isPresent();
  }

  @Test
  void anUnavailableDocumentIsRetriedThenReplacedByAPlaceholder() {
    BundleRequest request = BundleRequest.builder("Bundle")
        .document(BundleDocument.builder(DocSource.cdam(cdam.failing(503))).title("Outage").build())
        .build();

    DocJob done = runToEnd(jobs.submit(request, CASE_ID, "createBundle").id());

    assertThat(done.state()).isEqualTo(DocJobState.COMPLETED);
    assertThat(done.attempts()).isEqualTo(3);
    assertThat(cdam.fetchedAs).hasSize(3);
    assertThat(cdam.uploads).hasSize(1);
    assertThat(done.result().orElseThrow().failures()).extracting(DocFailure::title,
        DocFailure::reason).containsExactly(tuple("Outage", DocFailure.Reason.UNAVAILABLE));
  }

  @Test
  void aFailingHandlerIsCalledAgainWithTheSameDocument() {
    handler.failuresBeforeSuccess.set(1);
    DocConversionRequest request =
        DocConversionRequest.of(DocSource.bytes(fixture("one-page.pdf"), "letter.pdf"));

    DocJob done = runToEnd(jobs.submit(request, CASE_ID, "sendLetter").id());

    assertThat(done.state()).isEqualTo(DocJobState.COMPLETED);
    assertThat(done.attempts()).isEqualTo(2);
    assertThat(cdam.uploads).hasSize(1);
    assertThat(handler.completed).hasSize(2)
        .extracting(call -> call.uploaded().document().getUrl()).containsOnly(
            FakeCdam.HOST + cdam.uploads.getFirst().id());
  }

  @Test
  void aHandlerThatKeepsFailingFailsTheJob() {
    handler.failuresBeforeSuccess.set(Integer.MAX_VALUE);
    DocConversionRequest request =
        DocConversionRequest.of(DocSource.bytes(fixture("one-page.pdf"), "letter.pdf"));

    DocJob done = runToEnd(jobs.submit(request, CASE_ID, "sendLetter").id());

    assertThat(done.state()).isEqualTo(DocJobState.FAILED);
    assertThat(done.failure()).hasValueSatisfying(failure -> {
      assertThat(failure.reason()).isEqualTo("HANDLER_FAILED");
      assertThat(failure.message()).contains(FakeCdam.HOST);
    });
    assertThat(handler.failed).extracting(DocJob::id).containsExactly(done.id());
  }

  @Test
  void aPermanentFailureFailsTheJobAndTellsTheService() {
    BundleRequest request = BundleRequest.builder("Bundle")
        .document(BundleDocument.builder(DocSource.cdam(cdam.failing(404))).title("Gone").build())
        .missingDocuments(MissingDocuments.FAIL)
        .build();

    DocJob done = runToEnd(jobs.submit(request, CASE_ID, "createBundle").id());

    assertThat(done.state()).isEqualTo(DocJobState.FAILED);
    assertThat(done.attempts()).isEqualTo(1);
    assertThat(done.failure()).hasValueSatisfying(failure -> {
      assertThat(failure.reason()).isEqualTo("DOCUMENTS_FAILED");
      assertThat(failure.documents()).extracting(DocFailure::title, DocFailure::reason)
          .containsExactly(tuple("Gone", DocFailure.Reason.NOT_FOUND));
    });
    assertThat(handler.failed).extracting(DocJob::id).containsExactly(done.id());
    assertThat(handler.completed).isEmpty();
    assertThat(cdam.uploads).isEmpty();
  }

  @Test
  void aJobWhoseWorkersKeepDyingIsFailed() {
    UUID id = jobs.submit(DocConversionRequest.of(DocSource.bytes(fixture("one-page.pdf"),
        "letter.pdf")), CASE_ID, "sendLetter").id();
    // Three workers claim it and die: their leases run out without a result.
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThat(repository.claim(1, "dead-worker-" + attempt, Duration.ofMillis(1), 3))
          .hasSize(1);
      await().pollDelay(Duration.ofMillis(5)).until(() -> true);
    }

    worker.poll();

    DocJob done = jobs.find(id).orElseThrow();
    assertThat(done.state()).isEqualTo(DocJobState.FAILED);
    assertThat(done.failure().orElseThrow().reason()).isEqualTo("CRASHED");
    assertThat(handler.failed).extracting(DocJob::id).containsExactly(id);
  }

  @Test
  void aStoredRequestThatCannotBeReadFailsTheJob() {
    UUID id = jobs.submit(DocConversionRequest.of(DocSource.bytes(fixture("one-page.pdf"),
        "letter.pdf")), CASE_ID, "sendLetter").id();
    jdbc.update("update bundling.bundle_job set request = '{\"type\":\"spreadsheet\"}'::jsonb"
        + " where external_id = :id", Map.of("id", id));

    DocJob done = runToEnd(id);

    assertThat(done.state()).isEqualTo(DocJobState.FAILED);
    assertThat(done.failure().orElseThrow().reason()).isEqualTo("UNREADABLE_REQUEST");
  }

  @Test
  void aLocalFileCannotBeQueued() throws Exception {
    Path letter = Files.write(work.resolve("letter.pdf"), fixture("one-page.pdf"));

    assertThatThrownBy(() -> jobs.submit(DocConversionRequest.of(DocSource.file(letter)),
        CASE_ID, "sendLetter"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DocSource.bytes");
  }

  private DocJob runToEnd(UUID id) {
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(20)).until(() -> {
      worker.poll();
      DocJobState state = jobs.find(id).orElseThrow().state();
      return state == DocJobState.COMPLETED || state == DocJobState.FAILED;
    });
    return jobs.find(id).orElseThrow();
  }

  private record Completed(DocJob job, DocRequest request, UploadedDoc uploaded) {
  }

  private static final class RecordingHandler implements DocJobHandler {
    final List<Completed> completed = new CopyOnWriteArrayList<>();
    final List<DocJob> failed = new CopyOnWriteArrayList<>();
    final AtomicInteger failuresBeforeSuccess = new AtomicInteger();

    @Override
    public void completed(DocJob job, DocRequest request, UploadedDoc uploaded) {
      completed.add(new Completed(job, request, uploaded));
      if (failuresBeforeSuccess.getAndDecrement() > 0) {
        throw new IllegalStateException("the service's database is down");
      }
    }

    @Override
    public void failed(DocJob job) {
      failed.add(job);
    }
  }
}
