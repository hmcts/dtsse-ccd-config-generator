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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocConversionRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobHandler;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobs;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocResult;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkit;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkitException;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkitSystemUser;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.StubDocmosis;
import uk.gov.hmcts.ccd.sdk.doctoolkit.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.FakeCdam;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

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
          try (DocResult result = toolkit.render(
              DocConversionRequest.of(DocSource.file(word, "statement.docx", DOCX)))) {
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
          try (DocResult ignored = context.getBean(DocToolkit.class).render(
              DocConversionRequest.of(DocSource.file(word, "statement.docx", DOCX)))) {
            assertThat(own.calls()).hasSize(1);
          }
          assertThat(docmosisCalls).hasValue(0);
        });
  }

  @Test
  void withoutDocmosisOfficeDocumentsAreNotConfigured() {
    runner.run(context -> assertThatThrownBy(() -> context.getBean(DocToolkit.class).render(
            DocConversionRequest.of(DocSource.file(word, "statement.docx", DOCX))))
        .isInstanceOfSatisfying(DocToolkitException.class, e ->
            assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.NOT_CONFIGURED)));
  }

  @Test
  void cdamDocumentsAreFetchedWithTheApplicationsCdamBeans() {
    FakeCdam cdam = new FakeCdam();
    UUID id = cdam.store(fixture("one-page.pdf"), "application/pdf", "one-page.pdf");
    runner.withBean(CaseDocumentClientApi.class, () -> cdam)
        .withBean(AuthTokenGenerator.class, () -> () -> "Bearer service")
        .withBean(DocToolkitSystemUser.class, () -> () -> "Bearer system-user")
        .withBean(JdbcTemplate.class,
            () -> new JdbcTemplate(new DriverManagerDataSource("jdbc:unused")))
        .run(context -> {
          try (DocResult result = context.getBean(DocToolkit.class)
              .render(DocConversionRequest.of(DocSource.cdam(id)))) {
            assertThat(result.pageCount()).isEqualTo(1);
          }
          assertThat(cdam.fetchedAs).containsExactly("Bearer system-user Bearer service");
        });
  }

  @Test
  void withoutASystemUserCdamIsNotConfigured() {
    FakeCdam cdam = new FakeCdam();
    UUID id = cdam.store(fixture("one-page.pdf"), "application/pdf", "one-page.pdf");
    runner.withBean(CaseDocumentClientApi.class, () -> cdam)
        .withBean(AuthTokenGenerator.class, () -> () -> "Bearer service")
        .run(context -> assertThatThrownBy(() -> context.getBean(DocToolkit.class)
                .render(DocConversionRequest.of(DocSource.cdam(id))))
            .isInstanceOfSatisfying(DocToolkitException.class, e ->
                assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.NOT_CONFIGURED)));
  }

  @Test
  void jobsAreOffUnlessEnabled() {
    runner.withConfiguration(AutoConfigurations.of(DocJobsAutoConfiguration.class))
        .withBean(NamedParameterJdbcTemplate.class, DocToolkitAutoConfigurationTest::unusedJdbc)
        .run(context -> assertThat(context).doesNotHaveBean(DocJobs.class)
            .doesNotHaveBean(DocJobsAutoConfiguration.DocJobWorkerLifecycle.class));
  }

  @Test
  void anInstanceThatOnlyQueuesJobsNeedsNoHandler() {
    runner.withConfiguration(AutoConfigurations.of(DocJobsAutoConfiguration.class))
        .withBean(NamedParameterJdbcTemplate.class, DocToolkitAutoConfigurationTest::unusedJdbc)
        .withPropertyValues("ccd.doc-toolkit.jobs.enabled=true",
            "ccd.doc-toolkit.jobs.worker.enabled=false")
        .run(context -> assertThat(context).hasSingleBean(DocJobs.class)
            .doesNotHaveBean(DocJobsAutoConfiguration.DocJobWorkerLifecycle.class));
  }

  @Test
  void theWorkerNeedsTheServicesHandler() {
    runner.withConfiguration(AutoConfigurations.of(DocJobsAutoConfiguration.class))
        .withBean(NamedParameterJdbcTemplate.class, DocToolkitAutoConfigurationTest::unusedJdbc)
        .withPropertyValues("ccd.doc-toolkit.jobs.enabled=true")
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause().hasMessageContaining("one DocJobHandler bean"));
    runner.withConfiguration(AutoConfigurations.of(DocJobsAutoConfiguration.class))
        .withBean(NamedParameterJdbcTemplate.class, DocToolkitAutoConfigurationTest::unusedJdbc)
        .withBean(DocJobHandler.class, () -> (job, request, uploaded) -> { })
        .withPropertyValues("ccd.doc-toolkit.jobs.enabled=true")
        .run(context -> assertThat(context)
            .hasSingleBean(DocJobsAutoConfiguration.DocJobWorkerLifecycle.class));
  }

  private static NamedParameterJdbcTemplate unusedJdbc() {
    return new NamedParameterJdbcTemplate(new DriverManagerDataSource("jdbc:unused"));
  }

  private static byte[] pdfSaying() {
    try {
      return Fixtures.pdfSaying("Converted by the application's Docmosis");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
