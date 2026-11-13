package uk.gov.hmcts.ccd.sdk.bundling.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
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
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocumentPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.RenderAttempt;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/**
 * Renders through the real CDAM Feign client against a stub CDAM that serves good PDFs and
 * refuses one document with a fixed status.
 */
class CdamBundleDocumentResolverTest {

  private static final UUID REFUSED = UUID.randomUUID();

  @TempDir
  Path work;

  private HttpServer cdam;
  private int refusal;

  @BeforeEach
  void startStubCdam() throws IOException {
    byte[] pdf = onePagePdf();
    cdam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    cdam.createContext("/cases/documents/", exchange -> {
      if (exchange.getRequestURI().getPath().contains(REFUSED.toString())) {
        exchange.sendResponseHeaders(refusal, -1);
      } else {
        exchange.getResponseHeaders().add("Content-Type", "application/pdf");
        exchange.sendResponseHeaders(200, pdf.length);
        exchange.getResponseBody().write(pdf);
      }
      exchange.close();
    });
    cdam.start();
  }

  @AfterEach
  void stopStubCdam() {
    cdam.stop(0);
  }

  @Test
  void aCdamUnauthorisedFailsARetryableAttemptSoTheJobRetries() {
    refusal = 401;
    withRenderer(renderer -> {
      BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
          () -> renderer.render(request(), BundleExecutionContext.empty(), RenderAttempt.RETRYABLE));

      assertThat(failure).isNotNull();
      assertThat(failure.code()).isEqualTo(BundleErrorCode.CREDENTIALS_REJECTED);
    });
  }

  @Test
  void aCdamUnauthorisedStillFailsTheBundleOnTheFinalAttempt() {
    refusal = 401;
    withRenderer(renderer -> {
      BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
          () -> renderer.render(request(), BundleExecutionContext.empty(), RenderAttempt.FINAL));

      assertThat(failure).as("no bundle of placeholders is produced").isNotNull();
      assertThat(failure.code()).isEqualTo(BundleErrorCode.CREDENTIALS_REJECTED);
      assertThat(failure.documentFailures()).extracting(DocumentFailure::documentId)
          .containsExactly("refused");
      assertThat(entries(work)).as("nothing left behind").isEmpty();
    });
  }

  @Test
  void aCdamForbiddenFailsTheBundleEvenWhenTheCallerWouldRetry() {
    refusal = 403;
    withRenderer(renderer -> {
      BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
          () -> renderer.render(request(), BundleExecutionContext.empty(), RenderAttempt.RETRYABLE));

      assertThat(failure).as("no bundle of placeholders is produced").isNotNull();
      assertThat(failure.code()).isEqualTo(BundleErrorCode.DOCUMENT_ACCESS_DENIED);
      assertThat(failure.documentFailures()).extracting(DocumentFailure::documentId)
          .containsExactly("refused");
      assertThat(entries(work)).as("nothing left behind").isEmpty();
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
            .document(document("claim", UUID.randomUUID()))
            .document(document("refused", REFUSED))
            .document(document("statement", UUID.randomUUID()))
            .build())
        .build();
  }

  private static BundleDocument document(String id, UUID cdamId) {
    return BundleDocument.builder().id(id).title("Document " + id)
        .reference(new DocumentReference("cdam", cdamId.toString())).build();
  }

  private static byte[] onePagePdf() throws IOException {
    try (InputStream in = CdamBundleDocumentResolverTest.class
        .getResourceAsStream("/fixtures/em-stitching/one-page.pdf")) {
      return in.readAllBytes();
    }
  }

  private static java.util.List<Path> entries(Path directory) {
    try (Stream<Path> listed = Files.list(directory)) {
      return listed.toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableFeignClients(clients = CaseDocumentClientApi.class)
  static class Clients {
  }
}
