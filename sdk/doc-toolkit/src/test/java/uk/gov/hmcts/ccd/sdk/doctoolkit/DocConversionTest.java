package uk.gov.hmcts.ccd.sdk.doctoolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.assertNothingLeftBehind;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.fixture;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.load;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.text;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.StubDocmosis;

class DocConversionTest {
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  @TempDir
  private Path work;
  @TempDir
  private Path sources;

  private StubDocmosis docmosis;
  private DocToolkit toolkit;

  @BeforeEach
  void setUp() throws Exception {
    docmosis = StubDocmosis.returning(Fixtures.pdfSaying("Converted statement"),
        sources.resolve("docmosis"));
    toolkit = DocToolkit.builder().docmosis(docmosis).tempDirectory(work).build();
  }

  @Test
  void convertsOneDocumentToPdf() throws Exception {
    DocConversionRequest request =
        DocConversionRequest.of(DocSource.bytes(fixture("flying-pig.jpg"), "pig.jpg"));

    try (DocResult result = toolkit.render(request); PDDocument document = load(result)) {
      assertThat(result.fileName()).isEqualTo("pig.jpg.pdf");
      assertThat(result.pageCount()).isEqualTo(1);
      assertThat(document.getNumberOfPages()).isEqualTo(1);
    }
    assertNothingLeftBehind(work);
  }

  @Test
  void joinsDocumentsInOrderWithNothingAdded() throws Exception {
    Path word = Files.write(sources.resolve("statement.docx"), fixture("wordDocument2.docx"));
    DocConversionRequest request = DocConversionRequest
        .document(DocSource.bytes(Fixtures.pdfSaying("First page"), "first.pdf"))
        .document(DocSource.file(word, "statement.docx", DOCX))
        .document(HtmlPage.of("<p>Last page</p>"))
        .fileName("joined.pdf")
        .build();

    try (DocResult result = toolkit.render(request); PDDocument document = load(result)) {
      assertThat(result.pageCount()).isEqualTo(3);
      assertThat(text(document, 1)).contains("First page");
      assertThat(text(document, 2)).contains("Converted statement");
      assertThat(text(document, 3)).contains("Last page");
      assertThat(docmosis.calls()).extracting(StubDocmosis.Call::fileName)
          .containsExactly("statement.docx");
    }
  }

  @Test
  void aPrintedLetterKeepsTheCoversheetAndPadsEachPart() throws Exception {
    DocConversionRequest request = DocConversionRequest
        .unchanged(DocSource.bytes(Fixtures.pdfSaying("Jane Doe, 1 High Street"), "coversheet.pdf"))
        .document(DocSource.bytes(fixture("flying-pig.jpg"), "pig.jpg"))
        .document(DocSource.file(Fixtures.mixedLayoutPdf(sources)))
        .title("CPC-01 1234-5678-9012-3456")
        .print(PrintOptions.bulkPrint())
        .build();

    try (DocResult result = toolkit.render(request); PDDocument document = load(result)) {
      // Coversheet 1 + 1, photo 1 + 1, the three-page document 3 + 1.
      assertThat(result.pageCount()).isEqualTo(8);
      assertThat(text(document, 1)).contains("Jane Doe, 1 High Street");
      assertThat(text(document, 3)).isBlank();
      assertThat(document.getDocumentInformation().getTitle())
          .isEqualTo("CPC-01 1234-5678-9012-3456");
    }
  }

  @Test
  void aWatermarkIsAppliedBeforePrinting() throws Exception {
    Path image = Files.write(sources.resolve("draft.png"), fixture("schmcts.png"));
    DocConversionRequest request = DocConversionRequest
        .document(DocSource.bytes(Fixtures.pdfSaying("Order"), "order.pdf"))
        .watermark(Watermark.image(image))
        .print(PrintOptions.bulkPrint().toBuilder().padToEvenPages(false).build())
        .build();

    try (DocResult result = toolkit.render(request)) {
      assertThat(result.pageCount()).isEqualTo(1);
    }
  }

  @Test
  void aDocumentThatCannotBeConvertedFailsTheRequest() throws Exception {
    Path zip = Fixtures.zip(sources);
    DocConversionRequest request = DocConversionRequest
        .document(DocSource.bytes(Fixtures.pdfSaying("Fine"), "fine.pdf"))
        .document(DocSource.file(zip, "evidence.zip", null))
        .build();

    assertThatThrownBy(() -> toolkit.render(request))
        .isInstanceOfSatisfying(DocToolkitException.class, e -> {
          assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.DOCUMENTS_FAILED);
          assertThat(e.failures()).extracting(DocFailure::title, DocFailure::reason)
              .containsExactly(org.assertj.core.groups.Tuple.tuple("evidence.zip",
                  DocFailure.Reason.UNSUPPORTED_FORMAT));
        });
    assertNothingLeftBehind(work);
  }

  @Test
  void aLetterOverTheSizeLimitFails() {
    DocConversionRequest request = DocConversionRequest
        .document(DocSource.bytes(fixture("flying-pig.jpg"), "pig.jpg"))
        .print(PrintOptions.bulkPrint().toBuilder().maxOutputBytes(10_000).build())
        .build();

    assertThatThrownBy(() -> toolkit.render(request))
        .isInstanceOfSatisfying(DocToolkitException.class, e ->
            assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.OUTPUT_TOO_LARGE));
    assertNothingLeftBehind(work);
  }

  @Test
  void anHtmlPageNeverFetchesAnything() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.incrementAndGet();
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });
    server.start();
    try {
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      HtmlPage page = HtmlPage.builder().title("Recording")
          .body("<html><head><link rel='stylesheet' href='" + origin + "/style.css'/></head>"
              + "<body><img src='" + origin + "/logo.png'/><img src='file:///etc/passwd'/>"
              + "<p>Recording held by the court</p></body></html>")
          .build();

      try (DocResult result = toolkit.render(DocConversionRequest.of(page));
          PDDocument document = load(result)) {
        assertThat(text(document)).contains("Recording held by the court");
        assertThat(document.getDocumentInformation().getTitle()).isEqualTo("Recording");
      }
      assertThat(requests).hasValue(0);
    } finally {
      server.stop(0);
    }
  }
}
