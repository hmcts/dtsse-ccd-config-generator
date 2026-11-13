package uk.gov.hmcts.ccd.sdk.bundling.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocumentPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocumentReason;
import uk.gov.hmcts.ccd.sdk.bundling.api.RenderAttempt;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/** Renders through the real CDAM Feign client against a stub CDAM answering with a fixed status. */
class CdamBundleDocumentResolverTest {

  private static final UUID REJECTED = UUID.randomUUID();

  @TempDir
  Path work;

  private HttpServer cdam;
  private int status;

  @BeforeEach
  void startStubCdam() throws IOException {
    cdam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    cdam.createContext("/cases/documents/", exchange -> {
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    });
    cdam.start();
  }

  @AfterEach
  void stopStubCdam() {
    cdam.stop(0);
  }

  @Test
  void aCdamUnauthorisedFailsARetryableAttemptInsteadOfPublishingPlaceholders() {
    status = 401;
    withRenderer(renderer -> {
      BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
          () -> renderer.render(request(), BundleExecutionContext.empty(), RenderAttempt.RETRYABLE));

      assertThat(failure).isNotNull();
      assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_RESOLUTION_FAILED);
    });
  }

  @Test
  void aCdamUnauthorisedIsReplacedAsUnavailableOnTheFinalAttempt() {
    status = 401;
    withRenderer(renderer -> {
      try (BundleResult result =
          renderer.render(request(), BundleExecutionContext.empty(), RenderAttempt.FINAL)) {
        assertThat(result.missingDocuments()).extracting(MissingDocument::reason)
            .containsExactly(MissingDocumentReason.UNAVAILABLE);
      }
    });
  }

  @Test
  void aCdamForbiddenIsAPermanentAccessDenial() {
    status = 403;
    withRenderer(renderer -> {
      try (BundleResult result =
          renderer.render(request(), BundleExecutionContext.empty(), RenderAttempt.RETRYABLE)) {
        assertThat(result.missingDocuments()).extracting(MissingDocument::reason)
            .containsExactly(MissingDocumentReason.ACCESS_DENIED);
      }
    });
  }

  private void withRenderer(Consumer<BundleRenderer> test) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, Jackson2AutoConfiguration.class))
        .withUserConfiguration(Clients.class)
        .withPropertyValues("case_document_am.url=http://127.0.0.1:" + cdam.getAddress().getPort())
        .run(context -> test.accept(BundleRenderer.builder()
            .resolver(new CdamBundleDocumentResolver(
                context.getBean(CaseDocumentClientApi.class), () -> "service-token", () -> "Bearer user"))
            .tempDirectory(work)
            .build()));
  }

  private static BundleRequest request() {
    return BundleRequest.builder().externalId(UUID.randomUUID()).title("Bundle")
        .fileName("bundle.pdf").missingDocuments(MissingDocumentPolicy.PLACEHOLDER)
        .root(BundleSection.builder("Case file")
            .document(BundleDocument.builder().id("d1").title("Witness statement")
                .reference(new DocumentReference("cdam", REJECTED.toString())).build())
            .build())
        .build();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableFeignClients(clients = CaseDocumentClientApi.class)
  static class Clients {
  }
}
