package uk.gov.hmcts.ccd.sdk.conversion.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionSource;
import uk.gov.hmcts.ccd.sdk.conversion.ConvertedPdf;
import uk.gov.hmcts.ccd.sdk.conversion.DocumentConverter;
import uk.gov.hmcts.ccd.sdk.conversion.PrintOptions;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.PrintRequirements;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.StubDocmosis;

/** A Spring Boot service gets a working converter from properties alone. */
class ConversionAutoConfigurationTest {
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ConversionAutoConfiguration.class));
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @TempDir
  Path dir;

  @BeforeEach
  void startFakeDocmosis() throws Exception {
    byte[] pdf = Fixtures.pdfSaying("Converted over HTTP");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/rs/convert", exchange -> {
      requests.add(new String(exchange.getRequestBody().readAllBytes(),
          StandardCharsets.ISO_8859_1));
      exchange.getResponseHeaders().add("Content-Type", "application/pdf");
      exchange.sendResponseHeaders(200, pdf.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(pdf);
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void docmosisPropertiesGiveAConverterThatPrintsOfficeDocuments() {
    runner.withPropertyValues(
            "ccd.conversion.temp-directory=" + dir,
            "ccd.conversion.docmosis.convert-endpoint=http://127.0.0.1:"
                + server.getAddress().getPort() + "/rs/convert",
            "ccd.conversion.docmosis.access-key=secret-key")
        .run(context -> {
          DocumentConverter converter = context.getBean(DocumentConverter.class);
          assertThat(converter.supportedMediaTypes()).contains(DOCX, "text/csv");
          try (ConvertedPdf pdf = converter.toPrintReadyPdf(ConversionSource.of(
              Fixtures.real(dir, "wordDocument2.docx"), "letter.docx", DOCX),
              PrintOptions.bulkPrint())) {
            PrintRequirements.assertPrintReady(pdf.file(), PrintOptions.bulkPrint());
          }
          assertThat(requests).singleElement().asString()
              .contains("secret-key", "filename=\"letter.docx\"", "Content-Type: " + DOCX);
        });
  }

  @Test
  void withoutDocmosisTheConverterHandlesPdfsAndImagesOnly() {
    runner.run(context -> assertThat(context.getBean(DocumentConverter.class)
        .supportedMediaTypes()).contains("application/pdf", "image/jpeg")
        .doesNotContain(DOCX));
  }

  @Test
  void unsetDocmosisEnvVarsLeaveOfficeConversionOff() {
    // As a service's application.yaml writes them: ${DOCMOSIS_CONVERT_ENDPOINT:} binds "".
    runner.withPropertyValues("ccd.conversion.docmosis.convert-endpoint=",
            "ccd.conversion.docmosis.access-key=")
        .run(context -> {
          assertThat(context).hasNotFailed().doesNotHaveBean(DocmosisRenderService.class);
          assertThat(context.getBean(DocumentConverter.class).supportedMediaTypes())
              .doesNotContain(DOCX);
        });
  }

  @Test
  void anExistingDocmosisBeanIsReused() {
    StubDocmosis bundlingDocmosis = StubDocmosis.returning(new byte[0], dir);
    runner.withBean(DocmosisRenderService.class, () -> bundlingDocmosis)
        .withPropertyValues("ccd.conversion.docmosis.convert-endpoint=http://unused/rs/convert",
            "ccd.conversion.docmosis.access-key=unused")
        .run(context -> {
          assertThat(context).getBeans(DocmosisRenderService.class).hasSize(1);
          assertThat(context.getBean(DocmosisRenderService.class)).isSameAs(bundlingDocmosis);
          assertThat(context.getBean(DocumentConverter.class).supportedMediaTypes())
              .contains(DOCX);
        });
  }

  @Test
  void canBeSwitchedOff() {
    runner.withPropertyValues("ccd.conversion.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(DocumentConverter.class));
  }
}
