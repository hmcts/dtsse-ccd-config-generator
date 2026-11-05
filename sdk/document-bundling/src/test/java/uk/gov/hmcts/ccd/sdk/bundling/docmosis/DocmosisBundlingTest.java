package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
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
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisConnection;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.HttpDocmosisRenderService;

/**
 * Bundling with the Docmosis client against a fake Docmosis server. The client's own contract is
 * tested in document-conversion's {@code HttpDocmosisRenderServiceTest}.
 */
class DocmosisBundlingTest {
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
  void officeDocumentsAreConvertedThroughDocmosisIntoTheBundle() throws Exception {
    DocmosisConnection connection = DocmosisConnection.withDefaults(endpoint, ACCESS_KEY);
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
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).body())
        .contains("name=\"file\"; filename=\"wordDocument.doc\"\r\nContent-Type: " + DOC + "\r\n\r\n")
        .contains(new String(word, ISO_8859_1));
  }

  @Test
  void theCoverPageIsRenderedByDocmosisAndPrependedUnnumbered() throws Exception {
    DocmosisConnection connection = DocmosisConnection.withDefaults(endpoint, renderEndpoint, ACCESS_KEY);
    HttpDocmosisRenderService service = new HttpDocmosisRenderService(connection, outputDir);

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
    assertThat(requests).extracting(Recorded::path).containsExactly("/rs/convert", "/rs/render");
    assertThat(outputDir).isEmptyDirectory();
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
