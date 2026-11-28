package uk.gov.hmcts.ccd.sdk.doctoolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.assertNothingLeftBehind;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.fixture;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.load;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.text;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.SendLetterReplay;
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
    DocSource pig = DocSource.bytes(fixture("flying-pig.jpg"), "pig.jpg");
    PdfRequest request = PdfRequest.builder(pig.name()).document(pig).build();

    try (PdfResult result = toolkit.render(request); PDDocument document = load(result)) {
      assertThat(result.fileName()).isEqualTo("pig.jpg.pdf");
      assertThat(result.pageCount()).isEqualTo(1);
      assertThat(document.getNumberOfPages()).isEqualTo(1);
    }
    assertNothingLeftBehind(work);
  }

  @Test
  void joinsDocumentsInOrderWithNothingAdded() throws Exception {
    Path word = Files.write(sources.resolve("statement.docx"), fixture("wordDocument2.docx"));
    PdfRequest request = PdfRequest.builder("Joined")
        .document(DocSource.bytes(Fixtures.pdfSaying("First page"), "first.pdf"))
        .document(DocSource.file(word, "statement.docx", DOCX))
        .document(HtmlPage.of("<p>Last page</p>"))
        .fileName("joined.pdf")
        .build();

    try (PdfResult result = toolkit.render(request); PDDocument document = load(result)) {
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
    PdfRequest request = PdfRequest.builder("CPC-01 1234-5678-9012-3456")
        .document(PdfPart.builder(DocSource.bytes(Fixtures.pdfSaying("Jane Doe, 1 High Street"),
            "coversheet.pdf")).unchanged().build())
        .document(DocSource.bytes(fixture("flying-pig.jpg"), "pig.jpg"))
        .document(DocSource.file(Fixtures.mixedLayoutPdf(sources)))
        .print(PrintOptions.bulkPrint())
        .build();

    try (PdfResult result = toolkit.render(request); PDDocument document = load(result)) {
      // Coversheet 1 + 1, photo 1 + 1, the three-page document 3 + 1.
      assertThat(result.pageCount()).isEqualTo(8);
      assertThat(text(document, 1)).contains("Jane Doe, 1 High Street");
      assertThat(text(document, 3)).isBlank();
      assertThat(document.getDocumentInformation().getTitle())
          .isEqualTo("CPC-01 1234-5678-9012-3456");
    }
  }

  @Test
  void anExistingPhotoPdfKeepsItsJpegBytesAndDuplexPositionInThePrintPack() throws Exception {
    byte[] photo = fixture("flying-pig.jpg");
    var evidence = new ByteArrayOutputStream();
    try (var pdf = new PDDocument()) {
      var page = new PDPage(PDRectangle.A4);
      pdf.addPage(page);
      try (var content = new PDPageContentStream(pdf, page)) {
        content.drawImage(JPEGFactory.createFromStream(pdf, new ByteArrayInputStream(photo)),
            60, 60, 450, 600);
      }
      pdf.save(evidence);
    }
    var request = PdfRequest.builder("Claim pack")
        .document(PdfPart.builder(DocSource.bytes(Fixtures.pdfSaying("Address"),
            "cover.pdf")).unchanged().build())
        .document(PdfPart.builder(DocSource.bytes(evidence.toByteArray(),
            "evidence.pdf")).unchanged().build())
        .print(PrintOptions.builder().maxOutputBytes(photo.length + 32_768L).build())
        .build();
    try (PdfResult pack = toolkit.render(request); PDDocument pdf = load(pack)) {
      assertThat(pack.pageCount()).isEqualTo(4);
      assertThat(text(pdf, 1)).contains("Address");
      assertThat(pdf.getPage(1).hasContents()).isFalse();
      assertThat(pdf.getPage(3).hasContents()).isFalse();
      var resources = pdf.getPage(2).getResources();
      var name = resources.getXObjectNames().iterator().next();
      var image = (PDImageXObject) resources.getXObject(name);
      try (var raw = image.getCOSObject().createRawInputStream()) {
        assertThat(raw.readAllBytes()).isEqualTo(photo);
      }
      byte[] bytes = Files.readAllBytes(pack.file());
      assertThat(SendLetterReplay.uploadedBytes(List.of(bytes))).isEqualTo(bytes);
    }
  }

  @Test
  void aWatermarkIsAppliedBeforePrinting() throws Exception {
    Path image = Files.write(sources.resolve("draft.png"), fixture("schmcts.png"));
    PdfRequest request = PdfRequest.builder("Order")
        .document(DocSource.bytes(Fixtures.pdfSaying("Order"), "order.pdf"))
        .watermark(Watermark.image(image))
        .print(PrintOptions.bulkPrint().toBuilder().padToEvenPages(false).build())
        .build();

    try (PdfResult result = toolkit.render(request)) {
      assertThat(result.pageCount()).isEqualTo(1);
    }
  }

  @Test
  void aDocumentThatCannotBeConvertedFailsTheRequest() throws Exception {
    Path zip = Fixtures.zip(sources);
    PdfRequest request = PdfRequest.builder("Evidence")
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
    PdfRequest request = PdfRequest.builder("Letter")
        .document(DocSource.bytes(fixture("flying-pig.jpg"), "pig.jpg"))
        .print(PrintOptions.bulkPrint().toBuilder().maxOutputBytes(10_000).build())
        .build();

    assertThatThrownBy(() -> toolkit.render(request))
        .isInstanceOfSatisfying(DocToolkitException.class, e ->
            assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.OUTPUT_TOO_LARGE));
    assertNothingLeftBehind(work);
  }

  @Test
  void aPlainJoinCanHavePlaceholderPagesWhenNotPrinted() throws Exception {
    PdfRequest request = PdfRequest.builder("Evidence")
        .document(DocSource.bytes(Fixtures.pdfSaying("Fine"), "fine.pdf"))
        .document(DocSource.file(Fixtures.zip(sources), "evidence.zip", null))
        .missingDocuments(MissingDocuments.PLACEHOLDER)
        .build();

    try (PdfResult result = toolkit.render(request); PDDocument document = load(result)) {
      assertThat(result.pageCount()).isEqualTo(2);
      assertThat(text(document, 1)).contains("Fine");
      assertThat(text(document, 2)).contains(DocFailure.Reason.UNSUPPORTED_FORMAT.message());
      assertThat(result.failures()).singleElement().satisfies(failure ->
          assertThat(failure.page()).hasValue(2));
    }
  }

  @Test
  void aPrintedPlainJoinCannotHavePlaceholderPages() {
    PdfRequest.Builder request = PdfRequest.builder("Letter")
        .document(DocSource.bytes(fixture("flying-pig.jpg"), "pig.jpg"))
        .print(PrintOptions.bulkPrint())
        .missingDocuments(MissingDocuments.PLACEHOLDER);

    assertThatThrownBy(request::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPrintedPlainJoinCannotHaveTheConfidentialMarking() {
    PdfPart confidential = PdfPart.builder(DocSource.bytes(fixture("one-page.pdf"), "order.pdf"))
        .confidential().build();

    assertThatThrownBy(() -> PdfRequest.builder("Letter").document(confidential)
        .print(PrintOptions.bulkPrint()).confidentialMarking().build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(PdfRequest.builder("Printed bundle").document(confidential).titlePage()
        .print(PrintOptions.bulkPrint()).confidentialMarking().build().confidentialMarking())
        .isTrue();
  }

  @Test
  void unchangedPartsAreOnlyForAPrintedPlainJoin() {
    PdfPart coversheet = PdfPart.builder(DocSource.bytes(fixture("one-page.pdf"), "cover.pdf"))
        .unchanged().build();

    assertThatThrownBy(() -> PdfRequest.builder("Not printed").document(coversheet).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PdfRequest.builder("Printed bundle").document(coversheet)
        .titlePage().print(PrintOptions.bulkPrint()).build())
        .isInstanceOf(IllegalArgumentException.class);
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

      PdfRequest request = PdfRequest.builder(page.name()).document(page).build();
      try (PdfResult result = toolkit.render(request);
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
