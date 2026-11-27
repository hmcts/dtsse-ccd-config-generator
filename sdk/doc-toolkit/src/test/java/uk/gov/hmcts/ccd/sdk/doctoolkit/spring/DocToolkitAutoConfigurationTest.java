package uk.gov.hmcts.ccd.sdk.doctoolkit.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.fixture;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJob;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobHandler;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobs;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkit;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkitException;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfResult;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CdamSystemUserTokenProvider;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.StubDocmosis;
import uk.gov.hmcts.ccd.sdk.doctoolkit.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.FakeCdam;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.idam.client.IdamClient;

class DocToolkitAutoConfigurationTest {
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  @TempDir
  private Path temp;
  @TempDir
  private Path sources;

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DocToolkitAutoConfiguration.class));
  private final AtomicInteger docmosisCalls = new AtomicInteger();
  private HttpServer docmosis;
  private Path word;

  @BeforeEach
  void startDocmosis() throws Exception {
    byte[] pdf = Fixtures.pdfSaying("Converted by Docmosis");
    docmosis = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    docmosis.createContext("/rs/convert", exchange -> {
      docmosisCalls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "application/pdf");
      exchange.sendResponseHeaders(200, pdf.length);
      exchange.getResponseBody().write(pdf);
      exchange.close();
    });
    docmosis.start();
    word = Files.write(sources.resolve("statement.docx"), fixture("wordDocument2.docx"));
  }

  @AfterEach
  void stopDocmosis() {
    docmosis.stop(0);
  }

  private String convertEndpoint() {
    return "http://127.0.0.1:" + docmosis.getAddress().getPort() + "/rs/convert";
  }

  @Test
  void officeDocumentsGoThroughTheConfiguredDocmosis() {
    runner.withPropertyValues(
            "ccd.doc-toolkit.docmosis.convert-endpoint=" + convertEndpoint(),
            "ccd.doc-toolkit.docmosis.access-key=key",
            "ccd.doc-toolkit.temp-directory=" + temp)
        .run(context -> {
          DocToolkit toolkit = context.getBean(DocToolkit.class);
          try (PdfResult result = toolkit.render(
              single(DocSource.file(word, "statement.docx", DOCX)))) {
            assertThat(result.pageCount()).isEqualTo(1);
            assertThat(result.file()).startsWith(temp);
          }
          assertThat(docmosisCalls).hasValue(1);
        });
  }

  @Test
  void theApplicationsOwnDocmosisWins() {
    StubDocmosis own = StubDocmosis.returning(pdfSaying(), sources.resolve("own"));
    runner.withBean(DocmosisRenderService.class, () -> own)
        .withPropertyValues(
            "ccd.doc-toolkit.docmosis.convert-endpoint=" + convertEndpoint(),
            "ccd.doc-toolkit.docmosis.access-key=key")
        .run(context -> {
          try (PdfResult ignored = context.getBean(DocToolkit.class).render(
              single(DocSource.file(word, "statement.docx", DOCX)))) {
            assertThat(own.calls()).hasSize(1);
          }
          assertThat(docmosisCalls).hasValue(0);
        });
  }

  @Test
  void withoutDocmosisOfficeDocumentsAreNotConfigured() {
    runner.run(context -> assertThatThrownBy(() -> context.getBean(DocToolkit.class).render(
            single(DocSource.file(word, "statement.docx", DOCX))))
        .isInstanceOfSatisfying(DocToolkitException.class, e ->
            assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.NOT_CONFIGURED)));
  }

  @Test
  void cdamDocumentsAreFetchedWithTheApplicationsCdamBeans() {
    FakeCdam cdam = new FakeCdam();
    UUID id = cdam.store(fixture("one-page.pdf"), "application/pdf", "one-page.pdf");
    runner.withBean(CaseDocumentClientApi.class, () -> cdam)
        .withBean(AuthTokenGenerator.class, () -> () -> "Bearer service")
        .withBean(IdamClient.class, () -> new IdamClient(null, null) {
          @Override
          public String getAccessToken(String username, String password) {
            return "Bearer " + username + ":" + password;
          }
        })
        .withPropertyValues("ccd.doc-toolkit.system-user.username=system-user",
            "ccd.doc-toolkit.system-user.password=secret")
        .withBean(JdbcTemplate.class,
            () -> new JdbcTemplate(new DriverManagerDataSource("jdbc:unused")))
        .run(context -> {
          try (PdfResult result = context.getBean(DocToolkit.class)
              .render(single(DocSource.cdam(id)))) {
            assertThat(result.pageCount()).isEqualTo(1);
          }
          assertThat(cdam.fetchedAs).containsExactly("Bearer system-user:secret Bearer service");
        });
  }

  @Test
  void cdamCanUseTheApplicationsOwnSystemUserToken() {
    FakeCdam cdam = new FakeCdam();
    UUID id = cdam.store(fixture("one-page.pdf"), "application/pdf", "one-page.pdf");
    runner.withBean(CaseDocumentClientApi.class, () -> cdam)
        .withBean(AuthTokenGenerator.class, () -> () -> "Bearer service")
        .withBean(JdbcTemplate.class,
            () -> new JdbcTemplate(new DriverManagerDataSource("jdbc:unused")))
        .withBean(CdamSystemUserTokenProvider.class, () -> () -> "Bearer application-user")
        .run(context -> {
          try (PdfResult result = context.getBean(DocToolkit.class)
              .render(single(DocSource.cdam(id)))) {
            assertThat(result.pageCount()).isEqualTo(1);
          }
          assertThat(cdam.fetchedAs).containsExactly("Bearer application-user Bearer service");
        });
  }

  @Test
  void withoutASystemUserCdamIsNotConfigured() {
    FakeCdam cdam = new FakeCdam();
    UUID id = cdam.store(fixture("one-page.pdf"), "application/pdf", "one-page.pdf");
    runner.withBean(CaseDocumentClientApi.class, () -> cdam)
        .withBean(AuthTokenGenerator.class, () -> () -> "Bearer service")
        .run(context -> assertThatThrownBy(() -> context.getBean(DocToolkit.class)
                .render(single(DocSource.cdam(id))))
            .isInstanceOfSatisfying(DocToolkitException.class, e ->
                assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.NOT_CONFIGURED)));
  }

  @Test
  void jobsAreOffUnlessEnabled() {
    jobsRunner()
        .run(context -> assertThat(context).doesNotHaveBean(DocJobs.class)
            .doesNotHaveBean(DocJobsAutoConfiguration.DocJobWorkerLifecycle.class));
  }

  @Test
  void anInstanceThatOnlyQueuesJobsNeedsNoHandler() {
    jobsRunner()
        .withPropertyValues("ccd.doc-toolkit.jobs.enabled=true",
            "ccd.doc-toolkit.jobs.worker.enabled=false")
        .run(context -> assertThat(context).hasSingleBean(DocJobs.class)
            .doesNotHaveBean(DocJobsAutoConfiguration.DocJobWorkerLifecycle.class));
  }

  @Test
  void theWorkerNeedsTheServicesHandler() {
    jobsRunner()
        .withPropertyValues("ccd.doc-toolkit.jobs.enabled=true")
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause().hasMessageContaining("one DocJobHandler bean"));
    jobsRunner()
        .withBean(DocJobHandler.class, NoOpHandler::new)
        .withPropertyValues("ccd.doc-toolkit.jobs.enabled=true")
        .run(context -> assertThat(context)
            .hasSingleBean(DocJobsAutoConfiguration.DocJobWorkerLifecycle.class));
  }

  private static PdfRequest single(DocSource source) {
    return PdfRequest.builder(source.name()).document(source).build();
  }

  private static final class NoOpHandler implements DocJobHandler {
    @Override
    public void completed(DocJob job, UploadedDoc uploaded) {
    }
  }

  /** With the jobs configuration and a database it never reaches. */
  private ApplicationContextRunner jobsRunner() {
    DataSource unused = new DriverManagerDataSource("jdbc:unused");
    return runner.withConfiguration(AutoConfigurations.of(DocJobsAutoConfiguration.class))
        .withBean(DataSource.class, () -> unused)
        .withBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(unused));
  }

  private static byte[] pdfSaying() {
    try {
      return Fixtures.pdfSaying("Converted by the application's Docmosis");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
