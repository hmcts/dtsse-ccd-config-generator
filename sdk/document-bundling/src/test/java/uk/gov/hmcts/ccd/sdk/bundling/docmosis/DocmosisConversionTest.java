package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.CoverPage;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;

class DocmosisConversionTest {
  private static final String ACCESS_KEY = "super-secret-access-key";
  private static final String DOC = "application/msword";
  private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  private static final String CONVERTED_TEXT = "Converted by fake Docmosis";
  private static final byte[] SOURCE_BYTES = "real office document bytes".getBytes(UTF_8);
  private static final DocumentReference WORD_REF = new DocumentReference("fake", "word-1");

  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private final AtomicReference<Responder> responder = new AtomicReference<>();
  private HttpServer server;
  private URI endpoint;
  private URI renderEndpoint;
  private byte[] servedPdf;
  private Path source;
  @TempDir private Path outputDir;
  @TempDir private Path sourceDir;

  @BeforeEach
  void startFakeDocmosis() throws IOException {
    servedPdf = pdfSaying(CONVERTED_TEXT);
    responder.set(exchange -> respond(exchange, 200, servedPdf));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    com.sun.net.httpserver.HttpHandler recording = exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), ISO_8859_1);
      requests.add(new Recorded(exchange.getProtocol(), exchange.getRequestHeaders(), body,
          exchange.getRequestURI().getPath()));
      try {
        responder.get().respond(exchange);
      } catch (IOException | InterruptedException ignored) {
        // the client gave up (timeout scenarios); nothing to send to
      } finally {
        exchange.close();
      }
    };
    server.createContext("/rs/convert", recording);
    server.createContext("/rs/render", recording);
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rs/convert");
    renderEndpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rs/render");
    source = Files.write(sourceDir.resolve("letter.docx"), SOURCE_BYTES);
  }

  @AfterEach
  void stopFakeDocmosis() {
    server.stop(0);
  }

  @Test
  void convertSpeaksTheEmStitchingMultipartContractWithTheRealMediaType() throws Exception {
    DocmosisConnection connection = DocmosisConnection.withDefaults(endpoint, ACCESS_KEY);
    assertThat(connection.toString()).contains("accessKey=<redacted>").doesNotContain(ACCESS_KEY);
    Path result = new HttpDocmosisRenderService(connection, outputDir).convertToPdf(source, "letter.docx",
        DOCX + "\r\nX-Injected: owned\r\nContent-Transfer-Encoding: base64"); // CRLF in store metadata

    assertThat(result.getParent()).isEqualTo(outputDir);
    assertThat(Files.readAllBytes(result)).isEqualTo(servedPdf);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(result))).isEqualTo("rw-------");
    assertThat(requests).hasSize(1);
    Recorded request = requests.get(0);
    assertThat(request.protocol()).isEqualTo("HTTP/1.1");
    assertThat(request.headers().containsKey("Upgrade")).isFalse();
    assertThat(request.headers().containsKey("Http2-Settings")).isFalse();
    assertThat(request.headers().getFirst("Accept")).isEqualTo("application/pdf");
    assertThat(request.headers().getFirst("Content-Type")).startsWith("multipart/form-data; boundary=");
    String boundary = request.headers().getFirst("Content-Type").substring("multipart/form-data; boundary=".length());
    assertThat(request.body())
        .contains("--" + boundary + "\r\nContent-Disposition: form-data; name=\"accessKey\"\r\n\r\n"
            + ACCESS_KEY + "\r\n")
        .contains("name=\"outputName\"\r\n\r\nletter.docx.pdf\r\n")
        .contains("name=\"file\"; filename=\"letter.docx\"\r\nContent-Type: " + DOCX + "\r\n\r\n"
            + new String(SOURCE_BYTES, ISO_8859_1) + "\r\n")
        .doesNotContain("application/pdf").doesNotContain("X-Injected").doesNotContain("Content-Transfer-Encoding")
        .endsWith("--" + boundary + "--\r\n");

    // The same client wired through the pipeline: office handler -> Docmosis -> assembler.
    byte[] word = getClass().getResourceAsStream("/fixtures/em-stitching/wordDocument.doc").readAllBytes();
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolverServing("wordDocument.doc", word))
        .docmosis(new HttpDocmosisRenderService(connection, outputDir))
        .build();
    BundleRequest bundle = BundleRequest.builder()
        .externalId(UUID.randomUUID()).title("Docmosis bundle").fileName("bundle.pdf")
        .root(BundleSection.builder("Section A").document(
            BundleDocument.builder().id("d1").title("Word letter").reference(WORD_REF).build()).build())
        .build();
    try (BundleResult rendered = renderer.render(bundle, BundleExecutionContext.empty());
        InputStream in = rendered.artifact().open();
        PDDocument pdf = Loader.loadPDF(in.readAllBytes())) {
      assertThat(new PDFTextStripper().getText(pdf)).contains(CONVERTED_TEXT);
      assertThat(rendered.documents()).singleElement().satisfies(d -> assertThat(d.pageCount()).isEqualTo(1));
    }
    assertThat(requests).hasSize(2);
    assertThat(requests.get(1).body())
        .contains("name=\"file\"; filename=\"wordDocument.doc\"\r\nContent-Type: " + DOC + "\r\n\r\n")
        .contains(new String(word, ISO_8859_1));
  }

  @Test
  void renderSpeaksTheEmStitchingTemplateContractAndPrependsTheCoverPageThroughThePipeline() throws Exception {
    DocmosisConnection connection = DocmosisConnection.withDefaults(endpoint, renderEndpoint, ACCESS_KEY);
    assertThat(connection.toString()).contains("renderEndpoint=" + renderEndpoint).doesNotContain(ACCESS_KEY);
    HttpDocmosisRenderService service = new HttpDocmosisRenderService(connection, outputDir);
    assertThat(service.rendersTemplates()).isTrue();
    Path result = service.renderTemplate("FL-FRM-GOR-ENG-12345.docx",
        Map.of("caseReference", "1234", "hearingDate", java.time.LocalDate.of(2026, 3, 14)));

    assertThat(Files.readAllBytes(result)).isEqualTo(servedPdf);
    Files.delete(result);
    assertThat(requests).hasSize(1);
    Recorded request = requests.get(0);
    assertThat(request.path()).isEqualTo("/rs/render");
    assertThat(request.headers().getFirst("Content-Type")).startsWith("multipart/form-data; boundary=");
    String boundary = request.headers().getFirst("Content-Type").substring("multipart/form-data; boundary=".length());
    assertThat(request.body())
        .contains("name=\"templateName\"\r\n\r\nFL-FRM-GOR-ENG-12345.docx\r\n")
        .contains("name=\"accessKey\"\r\n\r\n" + ACCESS_KEY + "\r\n")
        .containsPattern("name=\"outputName\"\r\n\r\n[0-9a-f-]{36}\\.pdf\r\n")
        .contains("name=\"data\"\r\n\r\n{\"")
        .contains("\"caseReference\":\"1234\"").contains("\"hearingDate\":[2026,3,14]")
        .endsWith("--" + boundary + "--\r\n");

    // Through the pipeline: the cover page is rendered at CONVERT and placed first, unnumbered.
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolverServing("wordDocument.doc", getClass()
            .getResourceAsStream("/fixtures/em-stitching/wordDocument.doc").readAllBytes()))
        .docmosis(service).build();
    BundleRequest bundle = BundleRequest.builder()
        .externalId(UUID.randomUUID()).title("Docmosis bundle").fileName("bundle.pdf")
        .coverPage(new CoverPage("FL-FRM-GOR-ENG-12345.docx", Map.of("caseReference", "1234")))
        .root(BundleSection.builder("Section A").document(
            BundleDocument.builder().id("d1").title("Word letter").reference(WORD_REF).build()).build())
        .build();
    try (BundleResult rendered = renderer.render(bundle, BundleExecutionContext.empty());
        InputStream in = rendered.artifact().open();
        PDDocument pdf = Loader.loadPDF(in.readAllBytes())) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(1);
      stripper.setEndPage(1);
      assertThat(stripper.getText(pdf)).contains(CONVERTED_TEXT).doesNotContain("of " + pdf.getNumberOfPages());
      assertThat(pdf.getDocumentCatalog().getDocumentOutline().getFirstChild().getFirstChild().getTitle())
          .isEqualTo("Cover Page");
      assertThat(rendered.documents()).singleElement()
          .satisfies(d -> assertThat(d.startPage()).isEqualTo(pdf.getNumberOfPages()));
    }
    assertThat(requests).extracting(Recorded::path).containsExactly("/rs/render", "/rs/convert", "/rs/render");
    assertThat(outputDir).isEmptyDirectory();
  }

  @Test
  void clientErrorIsNonTransientServerErrorIsTransientAndNonPdfSuccessIsTypedFailure() {
    String secretBody = "TOP-SECRET-DOCMOSIS-DIAGNOSTIC";
    HttpDocmosisRenderService service = service(Duration.ofSeconds(10), DocmosisConnection.DEFAULT_MAX_SOURCE_BYTES);
    responder.set(exchange -> respond(exchange, 400, secretBody.getBytes(UTF_8)));
    final DocmosisRenderException clientError = catchThrowableOfType(DocmosisRenderException.class,
        () -> service.convertToPdf(source, "letter.docx", DOC));
    responder.set(exchange -> respond(exchange, 500, secretBody.getBytes(UTF_8)));
    final DocmosisRenderException serverError = catchThrowableOfType(DocmosisRenderException.class,
        () -> service.convertToPdf(source, "letter.docx", DOC));
    responder.set(exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "text/html");
      respond(exchange, 200, ("<html>" + secretBody + "</html>").getBytes(UTF_8));
    });
    DocmosisRenderException notPdf = catchThrowableOfType(DocmosisRenderException.class,
        () -> service.convertToPdf(source, "letter.docx", DOC));
    assertThat(clientError.isTransientFailure()).isFalse();
    assertThat(clientError.getMessage()).contains("400").contains("client error");
    assertThat(serverError.isTransientFailure()).isTrue();
    assertThat(serverError.getMessage()).contains("500").contains("server error");
    assertThat(notPdf.isTransientFailure()).isFalse();
    assertThat(notPdf.getMessage()).contains("200").contains("not a PDF").contains("text/html");
    for (DocmosisRenderException e : List.of(clientError, serverError, notPdf)) {
      assertThat(e.getMessage()).doesNotContain(secretBody).doesNotContain(ACCESS_KEY);
    }
    assertThat(requests).as("no retries: one request per call").hasSize(3);
    assertThat(outputDir).isEmptyDirectory();
  }

  @Test
  void theSourceSizeCeilingIsEnforcedBeforeAnyBytesAreSent() {
    HttpDocmosisRenderService service = service(Duration.ofSeconds(5), 10);
    DocmosisRenderException exception = catchThrowableOfType(DocmosisRenderException.class,
        () -> service.convertToPdf(source, "letter.docx", DOC));
    assertThat(exception.isTransientFailure()).isFalse();
    assertThat(exception.getMessage())
        .contains("letter.docx").contains(String.valueOf(SOURCE_BYTES.length)).contains("10");
    assertThat(requests).isEmpty();
  }

  @Test
  void stallMidBodyIsBoundedByTheReadTimeoutAndConnectionFailureIsTransient() throws Exception {
    responder.set(exchange -> {
      exchange.sendResponseHeaders(200, servedPdf.length);
      OutputStream out = exchange.getResponseBody();
      out.write(servedPdf, 0, 10);
      out.flush();
      Thread.sleep(1_500); // stall mid-body, six times the read timeout, with the connection held open
      out.write(servedPdf, 10, servedPdf.length - 10);
    });
    HttpDocmosisRenderService stalled = service(Duration.ofMillis(250), DocmosisConnection.DEFAULT_MAX_SOURCE_BYTES);
    long started = System.nanoTime();
    DocmosisRenderException timeout = catchThrowableOfType(DocmosisRenderException.class,
        () -> stalled.convertToPdf(source, "letter.docx", DOC));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    assertThat(timeout.isTransientFailure()).isTrue();
    assertThat(timeout.getMessage()).contains("timed out").contains("PT0.25S");

    server.stop(0); // nothing listens on the endpoint any more
    DocmosisRenderException refused = catchThrowableOfType(DocmosisRenderException.class,
        () -> stalled.convertToPdf(source, "letter.docx", DOC));
    assertThat(refused.isTransientFailure()).isTrue();
    assertThat(refused.getMessage()).contains("I/O error").doesNotContain(ACCESS_KEY);
    assertThat(outputDir).isEmptyDirectory();
  }

  private HttpDocmosisRenderService service(Duration readTimeout, long maxSourceBytes) {
    return new HttpDocmosisRenderService(
        new DocmosisConnection(endpoint, ACCESS_KEY, Duration.ofSeconds(2), readTimeout, maxSourceBytes), outputDir);
  }

  private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  private static byte[] pdfSaying(String text) throws IOException {
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 700);
        content.showText(text);
        content.endText();
      }
      document.save(out);
      return out.toByteArray();
    }
  }

  private static DocumentResolver resolverServing(String fileName, byte[] bytes) throws IOException {
    ResolvedDocument document = mock(ResolvedDocument.class);
    when(document.content()).thenReturn(new ByteArrayInputStream(bytes));
    when(document.mediaType()).thenReturn(DOC);
    when(document.fileName()).thenReturn(fileName);
    DocumentResolver resolver = mock(DocumentResolver.class);
    when(resolver.provider()).thenReturn(WORD_REF.provider());
    when(resolver.resolveAll(any(), any()))
        .thenReturn(ResolvedDocuments.allResolved(Map.of(WORD_REF, document)));
    return resolver;
  }

  private interface Responder {
    void respond(HttpExchange exchange) throws IOException, InterruptedException;
  }

  private record Recorded(String protocol, Headers headers, String body, String path) {
  }
}
