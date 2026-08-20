package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adversarial review counter-examples for {@link HttpDocmosisRenderService}. Each test pins a
 * behavioural rule from docs/bundling-stitching/document-bundling-module-design.md ("Docmosis
 * Integration"). Every {@code @Disabled} test was run on 2026-08-13 and FAILED, confirming the
 * referenced review finding; each asserts the desired contract, so it should be re-enabled with
 * the fix for its finding.
 */
class AdversarialReviewTest {

  private static final String ACCESS_KEY = "super-secret-access-key";
  private static final String DOCX_MEDIA_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  private static final byte[] PDF_BYTES = "%PDF-1.7 fake converted output".getBytes(ISO_8859_1);

  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @TempDir
  private Path outputDir;

  @TempDir
  private Path sourceDir;

  private Path source;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    source = Files.write(sourceDir.resolve("letter.docx"), "office bytes".getBytes(UTF_8));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  /**
   * FINDING 1: HttpRequest.timeout() in the JDK client only bounds the wait for the RESPONSE
   * HEADERS. Once a 200 and the first body bytes arrive, the body read has no timeout at all, so
   * a Docmosis that stalls mid-body hangs the bundling worker indefinitely. This violates the
   * design rule "Calls are bounded: connection/read timeouts" and the DocmosisConnection javadoc
   * ("readTimeout: how long to wait for the complete response").
   *
   * <p>The server below sends headers plus 10 bytes, then stalls for 4s — sixteen times the
   * configured 250ms read timeout. The contract requires the call to fail within the timeout
   * (plus slack); it instead blocks until the server chooses to finish.
   */
  @Test
  void aStallMidBodyMustBeBoundedByTheReadTimeout() throws Exception {
    server.createContext("/rs/convert", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] body = new byte[100];
      System.arraycopy(PDF_BYTES, 0, body, 0, 10);
      try {
        exchange.sendResponseHeaders(200, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body, 0, 10);
        out.flush();
        Thread.sleep(4_000); // stall mid-body, connection held open
        out.write(body, 10, body.length - 10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // client gave up
      } finally {
        exchange.close();
      }
    });

    HttpDocmosisRenderService service = new HttpDocmosisRenderService(
        connection(0, Duration.ofMillis(250)), outputDir);

    ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "adversarial-stall");
      t.setDaemon(true);
      return t;
    });
    try {
      Future<Object> call = executor.submit(() -> {
        try {
          return service.convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE);
        } catch (DocmosisRenderException e) {
          return e;
        }
      });
      // Generous slack: 250ms timeout should surface well within 2s.
      Object outcome = call.get(2, TimeUnit.SECONDS);
      assertThat(outcome).isInstanceOf(DocmosisRenderException.class);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * FINDING 2: a 200 response whose body is not a PDF (an HTML/JSON error page — the historical
   * em-stitching failure mode this module claims to fix) is accepted and returned as the
   * "converted PDF". The design rule says "Docmosis error responses are mapped to typed
   * conversion failures"; instead the garbage propagates into the bundle pipeline as a .pdf file.
   */
  @Test
  void aTwoHundredWithANonPdfBodyMustBeATypedFailure() throws Exception {
    byte[] errorPage = "<html><body>Conversion failed: no converter</body></html>".getBytes(UTF_8);
    server.createContext("/rs/convert", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "text/html");
      exchange.sendResponseHeaders(200, errorPage.length);
      exchange.getResponseBody().write(errorPage);
      exchange.close();
    });

    HttpDocmosisRenderService service = new HttpDocmosisRenderService(
        connection(0, Duration.ofSeconds(5)), outputDir);

    assertThatThrownBy(() -> service.convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE))
        .isInstanceOf(DocmosisRenderException.class);
  }

  /**
   * FINDING 3: the media type is written verbatim into the file part's headers. It comes from
   * document-store metadata (client-supplied at upload), so a crafted value containing CRLF
   * injects arbitrary headers into the authenticated Docmosis request. name/filename are
   * sanitised by quote(); contentType is not.
   */
  @Test
  void aCrLfInTheMediaTypeMustNotInjectPartHeaders() throws Exception {
    recordingContext("/rs/convert");
    HttpDocmosisRenderService service = new HttpDocmosisRenderService(
        connection(0, Duration.ofSeconds(5)), outputDir);

    service.convertToPdf(source, "letter.docx",
        "application/msword\r\nX-Injected: owned\r\nContent-Transfer-Encoding: base64");

    String wire = new String(requests.get(0).body(), ISO_8859_1);
    assertThat(wire).doesNotContain("\r\nX-Injected: owned\r\n");
    assertThat(wire).doesNotContain("\r\nContent-Transfer-Encoding: base64\r\n");
  }

  /**
   * FINDING 4 (wire fidelity): the JDK client defaults to HTTP/2, which over a plain-http
   * endpoint adds h2c upgrade headers (Upgrade / HTTP2-Settings / Connection) that OkHttp —
   * the client Docmosis actually sees from em-stitching — never sends.
   */
  @Test
  void theRequestMustNotCarryH2cUpgradeHeaders() throws Exception {
    recordingContext("/rs/convert");
    HttpDocmosisRenderService service = new HttpDocmosisRenderService(
        connection(0, Duration.ofSeconds(5)), outputDir);

    service.convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE);

    Headers headers = requests.get(0).headers();
    assertThat(headers.containsKey("Upgrade"))
        .as("h2c upgrade header sent: %s", headers.getFirst("Upgrade"))
        .isFalse();
    assertThat(headers.containsKey("Http2-Settings")).isFalse();
  }

  /**
   * FINDING 5: transient retries fire immediately — there is no backoff between attempts, so a
   * 503 from the shared platform Docmosis (overload) is answered with an instant re-POST of the
   * full multipart body. When run as a demonstration (asserting the gap is under 150ms) it
   * PASSES, confirming the hammering. As written here it asserts the desired contract — some
   * non-trivial delay before re-POSTing to an overloaded shared service (the exact backoff
   * policy is the implementer's choice; 50ms is a deliberately loose floor).
   */
  @Test
  void aTransientRetryMustBackOffBeforeRePosting() throws Exception {
    List<Long> arrivals = new CopyOnWriteArrayList<>();
    server.createContext("/rs/convert", exchange -> {
      exchange.getRequestBody().readAllBytes();
      arrivals.add(System.nanoTime());
      int status = arrivals.size() == 1 ? 503 : 200;
      exchange.sendResponseHeaders(status, PDF_BYTES.length);
      exchange.getResponseBody().write(PDF_BYTES);
      exchange.close();
    });
    HttpDocmosisRenderService service = new HttpDocmosisRenderService(
        connection(1, Duration.ofSeconds(5)), outputDir);

    service.convertToPdf(source, "letter.docx", DOCX_MEDIA_TYPE);

    assertThat(arrivals).hasSize(2);
    long gapMillis = (arrivals.get(1) - arrivals.get(0)) / 1_000_000;
    assertThat(gapMillis).as("gap between attempt 1 and retry").isGreaterThanOrEqualTo(50);
  }

  private void recordingContext(String path) {
    server.createContext(path, exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      requests.add(new Recorded(exchange.getRequestHeaders(), body));
      exchange.sendResponseHeaders(200, PDF_BYTES.length);
      exchange.getResponseBody().write(PDF_BYTES);
      exchange.close();
    });
  }

  private DocmosisConnection connection(int retryAttempts, Duration readTimeout) {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    return new DocmosisConnection(
        URI.create(base + "/rs/convert"),
        URI.create(base + "/rs/render"),
        ACCESS_KEY,
        Duration.ofSeconds(2),
        readTimeout,
        DocmosisConnection.DEFAULT_MAX_SOURCE_BYTES,
        retryAttempts);
  }

  private record Recorded(Headers headers, byte[] body) {
  }
}
