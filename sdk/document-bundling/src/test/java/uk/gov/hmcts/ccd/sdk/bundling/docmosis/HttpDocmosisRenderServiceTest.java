package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link HttpDocmosisRenderService} against a fake Docmosis served by the JDK's
 * built-in HTTP server. No SDK class is mocked.
 */
class HttpDocmosisRenderServiceTest {

  private static final String ACCESS_KEY = "super-secret-access-key";
  private static final String DOCX_MEDIA_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  private static final byte[] PDF_BYTES = "%PDF-1.7 fake converted output".getBytes(ISO_8859_1);
  private static final byte[] SOURCE_BYTES = "real office document bytes".getBytes(UTF_8);

  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Queue<CannedResponse> cannedResponses = new ConcurrentLinkedQueue<>();
  private HttpServer server;

  @TempDir
  private Path outputDir;

  @TempDir
  private Path sourceDir;

  private Path source;

  @BeforeEach
  void startFakeDocmosis() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpHandler handler = exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      requests.add(new RecordedRequest(
          exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body));
      CannedResponse response = cannedResponses.poll();
      if (response == null) {
        response = new CannedResponse(200, PDF_BYTES, 0);
      }
      try {
        if (response.delayMillis() > 0) {
          Thread.sleep(response.delayMillis());
        }
        exchange.sendResponseHeaders(response.status(), response.body().length);
        exchange.getResponseBody().write(response.body());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException e) {
        // The client gave up (e.g. the timeout test); nothing to send to.
      } finally {
        exchange.close();
      }
    };
    server.createContext("/rs/convert", handler);
    server.createContext("/rs/render", handler);
    server.start();
    source = Files.write(sourceDir.resolve("letter.docx"), SOURCE_BYTES);
  }

  @AfterEach
  void stopFakeDocmosis() {
    server.stop(0);
  }

  @Test
  void convertSpeaksTheEmStitchingMultipartContractWithTheRealMediaType() throws Exception {
    service(1).convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE);

    assertThat(requests).hasSize(1);
    RecordedRequest request = requests.get(0);
    assertThat(request.path()).isEqualTo("/rs/convert");
    assertThat(request.headers().getFirst("Accept")).isEqualTo("application/pdf");
    assertThat(request.headers().getFirst("Content-Type")).startsWith("multipart/form-data");

    String boundary = boundaryOf(request);
    String body = new String(request.body(), ISO_8859_1);
    assertThat(fieldValue(body, boundary, "accessKey")).isEqualTo(ACCESS_KEY);
    assertThat(fieldValue(body, boundary, "outputName")).isEqualTo("letter.docx.pdf");

    String fileHeaders = partHeaders(body, boundary, "file");
    assertThat(fileHeaders)
        .contains("name=\"file\"")
        .contains("filename=\"letter.docx\"")
        .contains("Content-Type: " + DOCX_MEDIA_TYPE)
        .doesNotContain("application/pdf");
    assertThat(body).contains(new String(SOURCE_BYTES, ISO_8859_1));
    assertThat(body).endsWith("--" + boundary + "--\r\n");
  }

  @Test
  void aConvertedResponseStreamsToAnOwnerOnlyFileInTheCallersDirectory() throws Exception {
    Path result = service(1).convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE);

    assertThat(result.getParent()).isEqualTo(outputDir);
    assertThat(Files.readAllBytes(result)).isEqualTo(PDF_BYTES);

    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    assertThat(Files.getPosixFilePermissions(result))
        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  }

  @Test
  void aFiveOhThreeIsRetriedExactlyOnceAndThenSucceeds() throws Exception {
    cannedResponses.add(new CannedResponse(503, "try later".getBytes(UTF_8), 0));

    Path result = service(1).convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE);

    assertThat(requests).hasSize(2);
    assertThat(Files.readAllBytes(result)).isEqualTo(PDF_BYTES);
  }

  @Test
  void theRetryBudgetIsBounded() {
    cannedResponses.add(new CannedResponse(503, new byte[0], 0));
    cannedResponses.add(new CannedResponse(503, new byte[0], 0));

    DocmosisRenderException exception = catchThrowableOfType(
        DocmosisRenderException.class,
        () -> service(1).convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE));

    assertThat(exception.isTransientFailure()).isTrue();
    assertThat(requests).hasSize(2);
  }

  @Test
  void aClientErrorFailsImmediatelyAsNonTransient() {
    cannedResponses.add(new CannedResponse(400, "bad template".getBytes(UTF_8), 0));

    DocmosisRenderException exception = catchThrowableOfType(
        DocmosisRenderException.class,
        () -> service(1).convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE));

    assertThat(exception.isTransientFailure()).isFalse();
    assertThat(exception.getMessage()).contains("400").contains("client error");
    assertThat(requests).hasSize(1);
  }

  @Test
  void theExceptionNeverLeaksTheResponseBodyOrTheAccessKey() {
    String secretBody = "TOP-SECRET-DOCMOSIS-DIAGNOSTIC";
    cannedResponses.add(new CannedResponse(500, secretBody.getBytes(UTF_8), 0));

    DocmosisRenderException exception = catchThrowableOfType(
        DocmosisRenderException.class,
        () -> service(0).convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE));

    assertThat(exception.isTransientFailure()).isTrue();
    assertThat(exception.getMessage())
        .contains("500")
        .doesNotContain(secretBody)
        .doesNotContain(ACCESS_KEY);
  }

  @Test
  void theSizeCeilingRejectsBeforeAnyHttpCall() {
    HttpDocmosisRenderService service = new HttpDocmosisRenderService(
        connection(1, 10, Duration.ofSeconds(5)), outputDir);

    DocmosisRenderException exception = catchThrowableOfType(
        DocmosisRenderException.class,
        () -> service.convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE));

    assertThat(exception.isTransientFailure()).isFalse();
    assertThat(exception.getMessage())
        .contains("letter.docx")
        .contains(String.valueOf(SOURCE_BYTES.length))
        .contains("10");
    assertThat(requests).isEmpty();
  }

  @Test
  void aReadTimeoutIsATransientFailure() {
    cannedResponses.add(new CannedResponse(200, PDF_BYTES, 2_000));

    DocmosisRenderException exception = catchThrowableOfType(
        DocmosisRenderException.class,
        () -> new HttpDocmosisRenderService(
            connection(0, DocmosisConnection.DEFAULT_MAX_SOURCE_BYTES, Duration.ofMillis(250)),
            outputDir)
            .convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE));

    assertThat(exception.isTransientFailure()).isTrue();
    assertThat(exception.getMessage()).contains("timed out");
  }

  @Test
  void aConnectionFailureIsATransientFailure() throws IOException {
    int unusedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      unusedPort = socket.getLocalPort();
    }
    URI dead = URI.create("http://127.0.0.1:" + unusedPort + "/rs/convert");
    HttpDocmosisRenderService service = new HttpDocmosisRenderService(
        new DocmosisConnection(dead, dead, ACCESS_KEY, Duration.ofSeconds(2),
            Duration.ofSeconds(2), DocmosisConnection.DEFAULT_MAX_SOURCE_BYTES, 0),
        outputDir);

    DocmosisRenderException exception = catchThrowableOfType(
        DocmosisRenderException.class,
        () -> service.convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE));

    assertThat(exception.isTransientFailure()).isTrue();
    assertThat(exception.getMessage()).doesNotContain(ACCESS_KEY);
  }

  @Test
  void renderTemplateSpeaksTheEmStitchingRenderContract() throws Exception {
    Path result = service(1).renderTemplate(
        "ST-CIC-ASS-ENG-Cover-Page.docx", Map.of("caseNumber", "1234"));

    assertThat(requests).hasSize(1);
    RecordedRequest request = requests.get(0);
    assertThat(request.path()).isEqualTo("/rs/render");

    String boundary = boundaryOf(request);
    String body = new String(request.body(), ISO_8859_1);
    assertThat(fieldValue(body, boundary, "templateName"))
        .isEqualTo("ST-CIC-ASS-ENG-Cover-Page.docx");
    assertThat(fieldValue(body, boundary, "accessKey")).isEqualTo(ACCESS_KEY);
    assertThat(fieldValue(body, boundary, "outputName")).endsWith(".pdf");
    assertThat(fieldValue(body, boundary, "data")).isEqualTo("{\"caseNumber\":\"1234\"}");
    assertThat(Files.readAllBytes(result)).isEqualTo(PDF_BYTES);
  }

  @Test
  void aMissingSourceFileFailsAsNonTransientWithoutCallingDocmosis() {
    assertThatThrownBy(() -> service(1)
        .convertToPdf(sourceDir.resolve("missing.docx"), "missing.docx", DOCX_MEDIA_TYPE))
        .isInstanceOf(DocmosisRenderException.class)
        .matches(e -> !((DocmosisRenderException) e).isTransientFailure());
    assertThat(requests).isEmpty();
  }

  private HttpDocmosisRenderService service(int retryAttempts) {
    return new HttpDocmosisRenderService(
        connection(retryAttempts, DocmosisConnection.DEFAULT_MAX_SOURCE_BYTES,
            Duration.ofSeconds(10)),
        outputDir);
  }

  private DocmosisConnection connection(int retryAttempts, long maxSourceBytes,
      Duration readTimeout) {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    return new DocmosisConnection(
        URI.create(base + "/rs/convert"),
        URI.create(base + "/rs/render"),
        ACCESS_KEY,
        Duration.ofSeconds(2),
        readTimeout,
        maxSourceBytes,
        retryAttempts);
  }

  private static String boundaryOf(RecordedRequest request) {
    String contentType = request.headers().getFirst("Content-Type");
    return contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
  }

  private static String fieldValue(String body, String boundary, String name) {
    String part = partOf(body, boundary, name);
    if (part == null) {
      return null;
    }
    String value = part.substring(part.indexOf("\r\n\r\n") + 4);
    return value.substring(0, value.lastIndexOf("\r\n"));
  }

  private static String partHeaders(String body, String boundary, String name) {
    String part = partOf(body, boundary, name);
    return part == null ? null : part.substring(0, part.indexOf("\r\n\r\n"));
  }

  private static String partOf(String body, String boundary, String name) {
    for (String part : body.split(Pattern.quote("--" + boundary))) {
      if (part.contains("name=\"" + name + "\"")) {
        return part;
      }
    }
    return null;
  }

  private record RecordedRequest(String path, Headers headers, byte[] body) {
  }

  private record CannedResponse(int status, byte[] body, long delayMillis) {
  }
}
