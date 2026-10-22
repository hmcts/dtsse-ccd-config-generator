package uk.gov.hmcts.ccd.sdk.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.Goldens;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.PrintFacts;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.PrintRequirements;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.StubDocmosis;

/**
 * Every kind of source the Possessions service uploads, converted for bulk print and checked two
 * ways: against the bulk print requirements ({@link PrintRequirements}), which hold for any
 * correct output, and against a golden of what this output looks like ({@link Goldens}), which
 * catches any change in layout, scaling, orientation, colour or page count.
 */
class PrintReadyGoldenTest {

  @TempDir
  static Path inputs;

  @TempDir
  Path work;

  interface Source {
    ConversionSource create(Path dir) throws IOException;
  }

  record Scenario(String name, Source source, PrintOptions options, int expectedPages) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Arguments> scenarios() {
    PrintOptions bulk = PrintOptions.bulkPrint();
    return Stream.of(
        // A phone-style photo, the commonest evidence upload.
        new Scenario("jpeg-photo", dir -> ConversionSource.of(
            Fixtures.real(dir, "flying-pig.jpg"), "flying-pig.jpg", "image/jpeg"), bulk, 2),
        // A 24 MP landscape photo: downsampled to 300 dpi and turned onto the portrait page.
        new Scenario("jpeg-large-landscape", dir -> ConversionSource.of(
            Fixtures.largePhoto(dir), "large-photo.jpg", "image/jpeg"), bulk, 2),
        // A palette PNG, and an RGBA PNG whose transparent background must print white.
        new Scenario("png-palette", dir -> ConversionSource.of(
            Fixtures.real(dir, "schmcts.png"), "schmcts.png", "image/png"), bulk, 2),
        new Scenario("png-alpha", dir -> ConversionSource.of(
            Fixtures.alphaPng(dir), "alpha.png", "image/png"), bulk, 2),
        new Scenario("bmp", dir -> ConversionSource.of(
            Fixtures.bmp(dir), "photo.bmp", "image/bmp"), bulk, 2),
        // Scanner output: portrait, landscape and a tiny frame, one page each.
        new Scenario("tiff-multi-frame", dir -> ConversionSource.of(
            Fixtures.multiPageTiff(dir), "scans.tiff", "image/tiff"), bulk, 4),
        // A real multi-page PDF.
        new Scenario("pdf-real-multi-page", dir -> ConversionSource.of(
            Fixtures.real(dir, "Potential_Energy_PDF.pdf"), "Potential_Energy_PDF.pdf",
            "application/pdf"), bulk, -1),
        // Full-bleed colour, a landscape page and a /Rotate 90 page.
        new Scenario("pdf-bleed-landscape-rotated", dir -> ConversionSource.of(
            Fixtures.mixedLayoutPdf(dir), "mixed-layout.pdf", "application/pdf"), bulk, 4),
        // Half-opaque fills and an alpha image: transparency must be flattened.
        new Scenario("pdf-transparency", dir -> ConversionSource.of(
            Fixtures.transparencyPdf(dir), "transparency.pdf", "application/pdf"), bulk, 2),
        // Word through (stub) Docmosis; the stub returns a real Docmosis-shaped PDF.
        new Scenario("docx-via-docmosis", dir -> ConversionSource.of(
            Fixtures.real(dir, "wordDocument2.docx"), "wordDocument2.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            bulk, 2),
        // Padding off: an odd page count stays odd.
        new Scenario("pdf-even-pages-unpadded", dir -> ConversionSource.of(
            Fixtures.mixedLayoutPdf(dir), "mixed-layout.pdf", "application/pdf"),
            bulk.toBuilder().padToEvenPages(false).build(), 3),
        // The other knobs: grey at 150 dpi, a wider margin, landscape kept landscape.
        new Scenario("pdf-grey-150dpi-25mm-no-rotate", dir -> ConversionSource.of(
            Fixtures.mixedLayoutPdf(dir), "mixed-layout.pdf", "application/pdf"),
            bulk.toBuilder().colour(PrintColour.GRAYSCALE).resolutionDpi(150)
                .marginMillimetres(25).rotateLandscapePages(false).build(), 4),
        // No PDF/X claim: same layout guarantees, no identification.
        new Scenario("jpeg-no-standard", dir -> ConversionSource.of(
            Fixtures.real(dir, "flying-pig.jpg"), "flying-pig.jpg", "image/jpeg"),
            bulk.toBuilder().standard(PdfStandard.NONE).build(), 2)
    ).map(Arguments::of);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void convertsForBulkPrint(Scenario scenario) throws Exception {
    Path dir = Files.createDirectories(inputs.resolve(scenario.name()));
    ConversionSource source = scenario.source().create(dir);
    DocumentConverter converter = DocumentConverter.builder()
        .docmosis(StubDocmosis.returning(
            Files.readAllBytes(Fixtures.real(dir, "one-page.pdf")), work.resolve("docmosis")))
        .tempDirectory(work)
        .build();

    try (ConvertedPdf pdf = converter.toPrintReadyPdf(source, scenario.options())) {
      if (scenario.expectedPages() > 0) {
        assertThat(pdf.pageCount()).isEqualTo(scenario.expectedPages());
      }
      assertThat(pdf.warnings()).isEmpty();
      PrintRequirements.assertPrintReady(pdf.file(), scenario.options());
      Goldens.verify(scenario.name(), pdf.file());
    }
  }

  /**
   * A bulk print letter as PCS sends it: the address coversheet as it is, then the supporting
   * documents converted. The coversheet page must be untouched, every other page print-ready.
   */
  @Test
  void assemblesALetterBehindAnUntouchedCoversheet() throws Exception {
    Path dir = Files.createDirectories(inputs.resolve("letter"));
    Path coversheet = Files.write(dir.resolve("coversheet.pdf"),
        Fixtures.pdfSaying("Coversheet: 1 Address Street, London, AB1 2CD"));
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work).build();

    try (ConvertedPdf letter = converter.toPrintReadyLetter("Claim pack", List.of(
        PrintPart.asIs(coversheet, "coversheet"),
        PrintPart.convert(ConversionSource.of(Fixtures.real(dir, "flying-pig.jpg"),
            "flying-pig.jpg", "image/jpeg")),
        PrintPart.convert(ConversionSource.of(Fixtures.mixedLayoutPdf(dir), "mixed-layout.pdf",
            "application/pdf"))), PrintOptions.bulkPrint())) {
      // 1 coversheet + 1 photo + 3 document pages, padded to 6.
      assertThat(letter.pageCount()).isEqualTo(6);
      PrintRequirements.assertPrintReady(letter.file(), PrintOptions.bulkPrint(), 2);
      try (PDDocument document = Loader.loadPDF(letter.file().toFile())) {
        assertThat(PrintFacts.pages(document).get(0)
            .hasText()).as("coversheet keeps its live text").isTrue();
      }
      assertThat(Goldens.compare(
          PrintFacts.render(coversheet, 72).get(0),
          PrintFacts.render(letter.file(), 72).get(0))
          .meanDifference()).as("coversheet renders exactly as it was").isZero();
      Goldens.verify("letter-coversheet-photo-pdf", letter.file());
    }
  }
}
