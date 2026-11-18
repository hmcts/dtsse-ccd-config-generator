package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocumentPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobRepository;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobWorker;
import uk.gov.hmcts.ccd.sdk.bundling.job.ManagedCaseBundles;
import uk.gov.hmcts.ccd.sdk.bundling.job.StoredBundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleSystemUser;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundles;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleView;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CdamBundleDocumentStore;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;
import uk.gov.hmcts.ccd.sdk.bundling.spring.BundlingAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.testing.CcdEventTestSupport;
import uk.gov.hmcts.ccd.sdk.testing.CcdSdkTest;
import uk.gov.hmcts.ccd.sdk.testing.TestDocuments;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGeneratorFactory;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

@CcdSdkTest(
    components = {
      ManagedCaseBundlesIntegrationTest.Config.class,
      ManagedCaseBundlesIntegrationTest.View.class,
      ManagedCaseBundlesIntegrationTest.Wiring.class
    })
@TestPropertySource(
    properties = {
      "ccd.bundling.job.enabled=true",
      "ccd.bundling.refresh=at-startup",
      "ccd.bundling.job.worker.poll-delay=1h",
      "ccd.bundling.job.worker.max-concurrent-renders=2",
      "ccd.bundling.job.retry.initial-delay=1ms",
      "ccd.bundling.job.retry.max-delay=1ms",
      "case_document_am.url=http://cdam.invalid",
      "idam.s2s-auth.url=http://s2s.invalid",
      "ccd.decentralised-runtime.system-user.id=bundle-worker",
      "ccd.decentralised-runtime.system-user.username=bundle-worker",
      "ccd.decentralised-runtime.system-user.first-name=Bundle",
      "ccd.decentralised-runtime.system-user.last-name=Worker"
    })
@ImportAutoConfiguration({
  BundlingAutoConfiguration.class,
  BundleJobAutoConfiguration.class,
  BundleJobFlywayAutoConfiguration.class,
  FeignAutoConfiguration.class,
  HttpMessageConvertersAutoConfiguration.class,
  Jackson2AutoConfiguration.class
})
class ManagedCaseBundlesIntegrationTest {
  @Autowired ManagedCaseBundles bundles;
  @Autowired BundleJobWorker worker;
  @Autowired BundleJobRepository jobs;
  @Autowired NamedParameterJdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;
  @Autowired CcdEventTestSupport<CaseData, State> events;
  @Autowired TestDocuments documents;
  @Autowired Store store;
  @Autowired Selection definition;

  @BeforeEach
  void prepare() {
    jdbc.getJdbcTemplate()
        .execute(
            """
create table if not exists bundle_test_source(reference bigint primary key, document_id uuid,
  title text)
""");
    store.failOnce.set(false);
    store.deleted.set(0);
    definition.calls.set(0);
  }

  @Test
  void changesCoalesceAndRollbackLeavesNoJob() throws Exception {
    long key = fixture();
    assertThat(bundles.view("case-file", key).lifecycle()).isEqualTo(CaseBundleView.Lifecycle.NONE);
    transaction.executeWithoutResult(
        status -> {
          bundles.changed("case-file", key);
          bundles.changed("case-file", key);
        });
    transaction.executeWithoutResult(
        status -> {
          bundles.changed("case-file", key);
          status.setRollbackOnly();
        });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from bundling.bundle_job where case_reference = :key",
                Map.of("key", key),
                Integer.class))
        .isEqualTo(1);
    assertThat(bundles.view("case-file", key).lifecycle())
        .isEqualTo(CaseBundleView.Lifecycle.PREPARING);
    run(key);
    assertThat(bundles.view("case-file", key).lifecycle())
        .isEqualTo(CaseBundleView.Lifecycle.UP_TO_DATE);
  }

  @Test
  void publishesAnAttachedPdfAndDeletesItHalfADayAfterItIsReplaced() throws Exception {
    long key = fixture();
    changed(key);
    run(key);
    var first = document(bundles.view("case-file", key));
    assertThat(documents.caseReference(first)).hasValue(key);
    assertThat(documents.content(first))
        .startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    assertThat(jobs.findLatestBundle("case-file", key).orElseThrow().caseReference()).isEqualTo(key);
    changed(key);
    assertThat(bundles.view("case-file", key).lifecycle())
        .isEqualTo(CaseBundleView.Lifecycle.UPDATING);
    run(key);
    var second = document(bundles.view("case-file", key));
    assertThat(second.getUrl()).isNotEqualTo(first.getUrl());
    bundles.cleanUp();
    // A reader may still have the replaced bundle open, so it is kept for a while.
    assertThat(documents.stored()).extracting(Document::getUrl).contains(first.getUrl());
    jdbc.update(
        "update bundling.bundle set created_at = created_at - interval '13 hours' "
            + "where case_reference = :key",
        Map.of("key", key));
    bundles.cleanUp();
    assertThat(documents.stored())
        .extracting(Document::getUrl)
        .doesNotContain(first.getUrl())
        .contains(second.getUrl());
  }

  @Test
  void aFailedStoreIsRetried() throws Exception {
    long key = fixture();
    Set<String> before = bundlePdfs();
    store.failOnce.set(true);
    changed(key);
    worker.poll();
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () -> jdbc.queryForObject("""
                select count(*) from bundling.bundle_job
                where case_reference = :key and attempts = 1 and lease_owner is null
                """, Map.of("key", key), Integer.class) == 1);
    assertThat(bundles.view("case-file", key).lifecycle())
        .isEqualTo(CaseBundleView.Lifecycle.PREPARING);
    run(key);
    String current = bundles.view("case-file", key).current().orElseThrow().stored().url();
    assertThat(newSince(before)).containsExactly(current);
  }

  @Test
  void aWorkerThatLostItsLeaseDeletesItsLateUpload() throws Exception {
    long key = fixture();
    Set<String> before = bundlePdfs();
    store.storeStarted = new CountDownLatch(1);
    store.releaseStore = new CountDownLatch(1);
    changed(key);
    worker.poll();
    assertThat(store.storeStarted.await(20, TimeUnit.SECONDS)).isTrue();
    jdbc.update(
        "update bundling.bundle_job set lease_expires_at = clock_timestamp() - interval '1 second' "
            + "where case_reference = :key",
        Map.of("key", key));
    run(key);
    String current = bundles.view("case-file", key).current().orElseThrow().stored().url();
    store.releaseStore.countDown();
    await().atMost(Duration.ofSeconds(20)).until(() -> store.deleted.get() == 1);
    assertThat(newSince(before)).containsExactly(current);
    store.storeStarted = null;
    store.releaseStore = null;
  }

  @Test
  void aSourceCdamCannotFindBecomesAMissingPage() throws Exception {
    long key = fixture();
    UUID source =
        jdbc.queryForObject(
            "select document_id from bundle_test_source where reference = :key",
            Map.of("key", key),
            UUID.class);
    documents.delete(
        documents.stored().stream()
            .filter(document -> document.getUrl().endsWith(source.toString()))
            .findFirst()
            .orElseThrow());
    changed(key);
    run(key);
    assertThat(bundles.view("case-file", key).current().orElseThrow().missingDocuments())
        .hasSize(1);
  }

  @Test
  void aChangeOutsideAnEventAndAnEventOnTheSameCaseDoNotDeadlock() throws Exception {
    long reference = fixture();
    var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var event = transaction.execute(status -> {
        // A write outside an event queues the bundle; an event then locks the case and queues it too.
        bundles.changed("case-file", reference);
        var running = pool.submit(() -> transaction.executeWithoutResult(inEvent -> {
          jdbc.query("select 1 from ccd.case_data where reference = :reference for update",
              Map.of("reference", reference), rs -> null);
          bundles.changed("case-file", reference);
        }));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
            jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'",
                Map.of(), Integer.class) > 0);
        return running;
      });
      event.get(10, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
      jdbc.update("delete from ccd.case_data where reference = :reference", Map.of("reference", reference));
    }
  }

  @Test
  void aChangeInTheEventThatCreatesACaseIsQueuedAndDeletingTheCaseDeletesItsJobs() {
    // The create handler runs before the runtime writes the case row.
    var creation = events.create("create", State.Open, new CaseData("created"));
    creation.submitExpectingSuccess();
    long reference = creation.reference();
    assertThat(bundles.view("case-file", reference).lifecycle())
        .isEqualTo(CaseBundleView.Lifecycle.PREPARING);

    jdbc.update("delete from ccd.case_data where reference = :reference", Map.of("reference", reference));
    assertThat(jobs.find("case-file", reference)).isEmpty();
  }

  @Test
  void aNewRefreshKeyRerendersEveryCaseOnceAndLeavesWaitingJobsAlone() throws Exception {
    long published = fixture();
    changed(published);
    run(published);
    final String before = document(bundles.view("case-file", published)).getUrl();
    long waiting = fixture();
    changed(waiting);
    Map<String, Object> cases = Map.of("cases", List.of(published, waiting));
    jdbc.update("delete from ccd.case_data where reference not in (:cases)", cases);

    // The configured ccd.bundling.refresh has run by the time the scheduled check returns.
    bundles.refreshIfNew();
    assertThat(bundles.refresh("at-startup")).isFalse();
    String key = UUID.randomUUID().toString();
    assertThat(bundles.refresh(key)).isTrue();
    assertThat(bundles.refresh(key)).isFalse();
    assertThat(bundles.refresh(" ")).isFalse();
    assertThat(jdbc.queryForList("""
        select priority from bundling.bundle_job order by priority
        """, Map.of(), Integer.class)).containsExactly(-1, 0);
    // The published PDF stays current until its replacement publishes.
    assertThat(bundles.view("case-file", published).lifecycle()).isEqualTo(CaseBundleView.Lifecycle.UPDATING);
    assertThat(document(bundles.view("case-file", published)).getUrl()).isEqualTo(before);
    run(waiting);
    run(published);
    assertThat(document(bundles.view("case-file", published)).getUrl()).isNotEqualTo(before);
  }

  @Test
  void theConfiguredRefreshKeyQueuesEveryCaseOnceHoweverManyPodsStartWithIt() throws Exception {
    long reference = fixture();
    jdbc.update("delete from ccd.case_data where reference <> :reference", Map.of("reference", reference));
    String release = UUID.randomUUID().toString();

    // What each pod does shortly after it starts with CCD_BUNDLING_REFRESH set.
    pod(release).refreshIfNew();
    assertThat(waitingRefreshes(reference)).isEqualTo(1);
    run(reference);
    // A second pod, or a restart, with the same value queues nothing.
    pod(release).refreshIfNew();
    assertThat(waitingRefreshes(reference)).isZero();
    // A pod with no value set queues nothing.
    pod("").refreshIfNew();
    assertThat(waitingRefreshes(reference)).isZero();
    // The next value refreshes again.
    pod(UUID.randomUUID().toString()).refreshIfNew();
    assertThat(waitingRefreshes(reference)).isEqualTo(1);
    run(reference);
  }

  private ManagedCaseBundles pod(String refreshKey) {
    return new ManagedCaseBundles(
        jobs, store, transaction.getTransactionManager(), List.of(definition), refreshKey);
  }

  private int waitingRefreshes(long reference) {
    return jdbc.queryForObject("""
        select count(*) from bundling.bundle_job
        where case_reference = :reference and priority = -1
        """, Map.of("reference", reference), Integer.class);
  }

  private static Document document(CaseBundleView view) {
    var stored = view.current().orElseThrow().stored();
    return Document.builder().url(stored.url()).binaryUrl(stored.binaryUrl()).build();
  }

  private Set<String> bundlePdfs() {
    return documents.stored().stream()
        .filter(document -> "bundle.pdf".equals(document.getFilename()))
        .map(Document::getUrl)
        .collect(java.util.stream.Collectors.toSet());
  }

  private Set<String> newSince(Set<String> before) {
    Set<String> added = new java.util.HashSet<>(bundlePdfs());
    added.removeAll(before);
    return added;
  }

  private void changed(long key) {
    transaction.executeWithoutResult(status -> bundles.changed("case-file", key));
  }

  private long fixture() throws Exception {
    long reference = events.seed(State.Open, new CaseData("fixture"));
    byte[] content;
    try (PDDocument pdf = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      pdf.addPage(new PDPage());
      pdf.save(output);
      content = output.toByteArray();
    }
    Document document = documents.store("source.pdf", "application/pdf", content);
    UUID id = UUID.fromString(document.getUrl().substring(document.getUrl().lastIndexOf('/') + 1));
    jdbc.update(
        "insert into bundle_test_source values (:reference, :id, 'source.pdf')",
        Map.of("reference", reference, "id", id));
    return reference;
  }

  private void run(long key) {
    worker.poll();
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> {
          worker.poll();
          return jobs.find("case-file", key).map(job -> job.failure().isPresent()).orElse(true);
        });
    // A bundle that is up to date has no queue row.
    assertThat(jobs.find("case-file", key)).isEmpty();
  }

  record CaseData(String value) {}

  enum State {
    Open
  }

  enum Role implements HasRole {
    Judge;

    @Override
    public String getRole() {
      return "judge";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }

  static class Config implements CCDConfig<CaseData, State, Role> {
    @Autowired CaseBundles bundles;

    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<CaseData, State, Role> builder) {
      builder.caseType("ManagedTest", "ManagedTest", "ManagedTest");
      builder.jurisdiction("TEST", "Test", "Test");
      builder.event("create").initialState(State.Open).aboutToSubmitCallback((details, before) -> {
        bundles.changed("case-file", details.getId());
        return AboutToStartOrSubmitResponse.<CaseData, State>builder().data(details.getData()).build();
      });
    }
  }

  static class View implements CaseView<CaseData, State> {
    @Override
    public Set<String> caseTypeIds() {
      return Set.of("ManagedTest");
    }

    @Override
    public CaseData getCase(CaseViewRequest<State> request, CaseData blob) {
      return blob;
    }
  }

  static class Selection implements CaseBundleDefinition {
    final NamedParameterJdbcTemplate jdbc;
    final AtomicInteger calls = new AtomicInteger();

    Selection(NamedParameterJdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public String name() {
      return "case-file";
    }

    @Override
    public String caseType() {
      return "ManagedTest";
    }

    @Override
    public BundleRequest select(long caseReference) {
      calls.incrementAndGet();
      return jdbc.queryForObject(
          "select * from bundle_test_source where reference = :reference",
          Map.of("reference", caseReference),
          (row, n) ->
                  BundleRequest.builder()
                      .title("Case bundle")
                      .fileName("bundle.pdf")
                      .missingDocuments(MissingDocumentPolicy.PLACEHOLDER)
                      .root(
                          BundleSection.builder("Case file")
                              .document(
                                  BundleDocument.builder()
                                      .id("source")
                                      .title(row.getString("title"))
                                      .reference(
                                          new DocumentReference(
                                              "cdam", row.getString("document_id")))
                                      .build())
                              .build())
                      .build());
    }
  }

  static class Store extends CdamBundleDocumentStore {
    final AtomicBoolean failOnce = new AtomicBoolean();
    final AtomicInteger deleted = new AtomicInteger();
    volatile CountDownLatch storeStarted;
    volatile CountDownLatch releaseStore;

    Store(CaseDocumentClientApi client, AuthTokenGenerator tokens, BundleSystemUser user) {
      super(client, tokens, user);
    }

    @Override
    public StoredBundleDocument store(SelectedCaseBundle selection, BundleArtifact artifact) {
      if (failOnce.getAndSet(false)) {
        throw new IllegalStateException("Temporary storage failure");
      }
      CountDownLatch started = storeStarted;
      CountDownLatch release = releaseStore;
      if (started != null && started.getCount() > 0) {
        started.countDown();
        try {
          if (!release.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Store barrier timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }
      return super.store(selection, artifact);
    }

    @Override
    public void delete(UUID documentId) {
      super.delete(documentId);
      deleted.incrementAndGet();
    }
  }

  @TestConfiguration
  @EnableFeignClients(clients = {CaseDocumentClientApi.class, ServiceAuthorisationApi.class})
  static class Wiring {
    @Bean
    TransactionTemplate transaction(
        org.springframework.transaction.PlatformTransactionManager manager) {
      return new TransactionTemplate(manager);
    }

    @Bean
    BundleSystemUser bundleUser() {
      return () -> CcdEventTestSupport.DEFAULT_AUTHORISATION;
    }

    @Bean
    AuthTokenGenerator tokens(ServiceAuthorisationApi client) {
      return AuthTokenGeneratorFactory.createDefaultGenerator("AAAAAAAAAAAAAAAA", "test", client);
    }

    @Bean
    Selection selection(NamedParameterJdbcTemplate jdbc) {
      return new Selection(jdbc);
    }

    @Bean
    Store store(CaseDocumentClientApi client, AuthTokenGenerator tokens, BundleSystemUser user) {
      return new Store(client, tokens, user);
    }
  }
}
