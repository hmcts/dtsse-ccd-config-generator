package uk.gov.hmcts.ccd.sdk.doctoolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.ONE_CASE_TYPE;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.SERVICE_TOKENS;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.SYSTEM_USER;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.assertNothingLeftBehind;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.fixture;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.load;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.outline;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.FakeCdam;
import uk.gov.hmcts.ccd.sdk.type.Document;

class BundleTest {
  @TempDir
  private Path work;
  @TempDir
  private Path sources;

  private final FakeCdam cdam = new FakeCdam();
  private DocToolkit toolkit;
  private int reportPages;
  private UUID cover;
  private UUID report;

  @BeforeEach
  void setUp() throws Exception {
    toolkit = DocToolkit.builder().tempDirectory(work)
        .cdam(cdam, SERVICE_TOKENS, SYSTEM_USER, ONE_CASE_TYPE).build();
    cover = cdam.store(fixture("one-page.pdf"), "application/pdf", "cover.pdf");
    // A parameterised type: parameters are ignored.
    report = cdam.store(fixture("Potential_Energy_PDF.pdf"), "application/pdf;charset=UTF-8",
        "report.pdf");
    try (PDDocument document = Loader.loadPDF(fixture("Potential_Energy_PDF.pdf"))) {
      reportPages = document.getNumberOfPages();
    }
  }

  @Test
  void rendersSectionsFromCdamLocalFilesAndHtmlPages() throws Exception {
    Path photo = Files.write(sources.resolve("photo.jpg"), fixture("flying-pig.jpg"));
    BundleRequest request = BundleRequest.builder("Final hearing bundle")
        .fileName("final-hearing-bundle.pdf")
        .document(BundleDocument.builder(DocSource.of(Document.builder()
                .url(FakeCdam.HOST + cover).filename("cover-letter.pdf").build()))
            .title("Cover letter").date(LocalDate.of(2026, 3, 14)).build())
        .section(BundleSection.builder("Evidence")
            .document(BundleDocument.builder(DocSource.cdam(report)).title("Expert report").build())
            .document(BundleDocument.builder(DocSource.file(photo)).title("Site photograph").build())
            .document(BundleDocument.builder(DocSource.bytes(fixture("schmcts.png"), "logo.png"))
                .title("Court logo").build())
            .build())
        .section(BundleSection.builder("Recordings")
            .document(HtmlPage.builder().title("Hearing recording, day 2")
                .body("<h1>Hearing recording, day 2</h1><p>Held by the court: 42 minutes.</p>")
                .build())
            .build())
        .section(BundleSection.builder("Correspondence").placeholderWhenEmpty().build())
        .build();

    DocResult result = toolkit.render(request);
    try (result; PDDocument document = load(result)) {
      assertThat(result.fileName()).isEqualTo("final-hearing-bundle.pdf");
      assertThat(result.failures()).isEmpty();
      // Title, contents, cover letter, Evidence cover sheet, the report, photo and logo, the
      // Recordings cover sheet, the HTML page, then the empty section's placeholder.
      assertThat(result.pageCount()).isEqualTo(9 + reportPages);
      assertThat(document.getNumberOfPages()).isEqualTo(result.pageCount());
      assertThat(text(document)).contains("Final hearing bundle", "Index Page", "Cover letter",
          "Expert report", "Held by the court: 42 minutes.", "Correspondence",
          "There are no documents in this section.");
      assertThat(text(document, 8 + reportPages)).contains("Held by the court: 42 minutes.");
      assertThat(outline(document)).contains("Cover letter", "Evidence", "Expert report",
          "Site photograph", "Court logo", "Recordings", "Hearing recording, day 2");
      assertThat(cdam.fetchedAs).containsOnly("Bearer system-user Bearer service");
      try (Stream<Path> entries = Files.list(work)) {
        assertThat(entries).singleElement().satisfies(dir ->
            assertThat(dir.getFileName().toString()).startsWith("ccd-doc-toolkit-"));
      }
    }
    assertNothingLeftBehind(work);
  }

  @Test
  void aDocumentIsListedUnderItsFileNameWhenGivenNoTitle() throws Exception {
    BundleRequest request = BundleRequest.builder("Bundle")
        .document(Document.builder().url(FakeCdam.HOST + cover).filename("Claim form.pdf").build())
        .build();

    try (DocResult result = toolkit.render(request); PDDocument document = load(result)) {
      assertThat(outline(document)).contains("Claim form.pdf");
      assertThat(result.fileName()).isEqualTo("Bundle.pdf");
    }
  }

  @Test
  void theCoverPageComesFirstBeforeTheTitleAndContents() throws Exception {
    BundleRequest request = BundleRequest.builder("Bundle")
        .coverPage(HtmlPage.builder().body("<p>Front sheet for the court</p>").build())
        .document(DocSource.cdam(cover))
        .build();

    try (DocResult result = toolkit.render(request); PDDocument document = load(result)) {
      assertThat(text(document, 1)).contains("Front sheet for the court");
      assertThat(outline(document)).startsWith("Bundle", "Cover Page");
    }
  }

  @Test
  void theWatermarkMarksDocumentPagesOnly() throws Exception {
    Path image = Files.write(sources.resolve("draft.png"), fixture("schmcts.png"));
    BundleRequest request = BundleRequest.builder("Bundle")
        .section(BundleSection.builder("Evidence")
            .document(DocSource.bytes(Fixtures.pdfSaying("Evidence"), "evidence.pdf")).build())
        .watermark(Watermark.image(image))
        .build();

    try (DocResult result = toolkit.render(request); PDDocument document = load(result)) {
      // Title, contents and section cover sheet are generated; only the document is marked.
      assertThat(images(document, 0)).isZero();
      assertThat(images(document, 1)).isZero();
      assertThat(images(document, 2)).isZero();
      assertThat(images(document, 3)).isEqualTo(1);
    }
  }

  @Test
  void theContentDecidesTheTypeWhenTheDeclarationIsWrong() throws Exception {
    Path photo = Files.write(sources.resolve("photo.pdf"), fixture("flying-pig.jpg"));
    BundleRequest request = BundleRequest.builder("Bundle")
        .document(DocSource.file(photo, "photo.pdf", "application/pdf"))
        .build();

    try (DocResult result = toolkit.render(request)) {
      assertThat(result.failures()).isEmpty();
    }
  }

  @Test
  void aPrintedBundleIsLaidOutForBulkPrint() throws Exception {
    BundleRequest request = BundleRequest.builder("Bundle")
        .tableOfContents(false)
        .document(DocSource.cdam(report))
        .print(PrintOptions.bulkPrint())
        .build();

    try (DocResult result = toolkit.render(request); PDDocument document = load(result)) {
      // Title page plus the report, padded to an even count.
      int pages = 1 + reportPages;
      assertThat(result.pageCount()).isEqualTo(pages + pages % 2);
      assertThat(text(document)).isBlank();
      assertThat(document.getPage(1).getResources().getXObjectNames()).hasSize(1);
    }
  }

  @Test
  void aBundleWithNothingInItIsRejected() {
    BundleRequest request = BundleRequest.builder("Empty")
        .section(BundleSection.builder("Nothing").build())
        .build();

    assertThatThrownBy(() -> toolkit.render(request))
        .isInstanceOfSatisfying(DocToolkitException.class, e ->
            assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.INVALID_REQUEST));
    assertNothingLeftBehind(work);
  }

  private static int images(PDDocument document, int pageIndex) throws Exception {
    return images(document.getPage(pageIndex).getResources());
  }

  // The watermark overlay is a form XObject holding the image.
  private static int images(PDResources resources) throws Exception {
    int count = 0;
    for (COSName name : resources.getXObjectNames()) {
      PDXObject xobject = resources.getXObject(name);
      if (xobject instanceof PDImageXObject) {
        count++;
      } else if (xobject instanceof PDFormXObject form) {
        count += images(form.getResources());
      }
    }
    return count;
  }
}
