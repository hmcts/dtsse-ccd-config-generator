package uk.gov.hmcts.ccd.sdk.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException.Reason;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionWarning.Code;
import uk.gov.hmcts.ccd.sdk.conversion.detect.MediaTypeDetection;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.Goldens;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.PdfX1aChecker;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.PrintFacts;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.PrintRequirements;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.SendLetterReplay;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.StubDocmosis;

/** What the converter does with each kind of input, from the caller's side. */
class DocumentConverterTest {
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  @TempDir
  Path inputs;

  @TempDir
  Path work;

  private StubDocmosis docmosis;
  private DocumentConverter converter;

  @BeforeEach
  void setUp() throws IOException {
    docmosis = StubDocmosis.returning(Fixtures.pdfSaying("Converted by Docmosis"),
        inputs.resolve("docmosis-out"));
    converter = DocumentConverter.builder().docmosis(docmosis).tempDirectory(work).build();
  }

  /**
   * Every file extension the Possessions service accepts as supporting evidence, inferred from
   * the file name alone (as when the store has no media type), converts to a print-ready PDF.
   * Office formats reach Docmosis with the media type that names them.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"jpg", "jpeg", "bmp", "tif", "tiff", "png", "doc", "dot", "docx",
      "dotx", "pdf", "xls", "xlt", "xla", "xlsx", "xltx", "xlsb", "ppt", "pot", "pps", "ppa",
      "pptx", "potx", "ppsx", "txt", "rtf", "csv"})
  void everyPossessionsUploadTypeConvertsForPrint(String extension) throws Exception {
    Path file = sampleFor(extension);
    String expectedType = MediaTypeDetection.fromFileName("x." + extension).orElseThrow();

    try (ConvertedPdf pdf = converter.toPrintReadyPdf(
        ConversionSource.of(file, "evidence." + extension, "application/octet-stream"),
        PrintOptions.bulkPrint())) {
      assertThat(pdf.mediaType()).isEqualTo(expectedType);
      assertThat(converter.supportedMediaTypes()).contains(expectedType);
      assertThat(pdf.warnings()).extracting(ConversionWarning::code)
          .containsExactly(Code.MEDIA_TYPE_INFERRED);
      PrintRequirements.assertPrintReady(pdf.file(), PrintOptions.bulkPrint());
    }
    if (SupportedMediaTypes.OFFICE.contains(expectedType)) {
      assertThat(docmosis.calls()).containsExactly(
          new StubDocmosis.Call("evidence." + extension, expectedType));
    } else {
      assertThat(docmosis.calls()).isEmpty();
    }
  }

  @Test
  void plainConversionKeepsAPdfByteForByteAndLaysImagesOutAsBundlingDoes() throws Exception {
    Path source = Fixtures.real(inputs, "one-page.pdf");
    try (ConvertedPdf pdf = converter.toPdf(ConversionSource.of(source, "one-page.pdf",
        "application/pdf"))) {
      assertThat(Files.readAllBytes(pdf.file())).isEqualTo(Files.readAllBytes(source));
      assertThat(pdf.pageCount()).isEqualTo(1);
    }
    try (ConvertedPdf pdf = converter.toPdf(ConversionSource.of(
        Fixtures.multiPageTiff(inputs), "scans.tiff", "image/tiff"));
        PDDocument document = Loader.loadPDF(pdf.file().toFile())) {
      // One Letter page per frame, natural size, centred: em-stitching's image layout.
      assertThat(pdf.pageCount()).isEqualTo(3);
      assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(612f);
      assertThat(PrintFacts.pages(document)).extracting(page -> page.images().size())
          .containsExactly(1, 1, 1);
    }
  }

  @Test
  void contentWinsOverAWrongDeclaredTypeWithAWarning() throws Exception {
    Path png = Fixtures.alphaPng(inputs);
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(
        ConversionSource.of(png, "photo.jpg", "image/jpeg"), PrintOptions.bulkPrint())) {
      assertThat(pdf.mediaType()).isEqualTo("image/png");
      assertThat(pdf.warnings()).singleElement().satisfies(warning -> {
        assertThat(warning.code()).isEqualTo(Code.MEDIA_TYPE_MISMATCH);
        assertThat(warning.message()).contains("photo.jpg", "image/jpeg", "image/png");
      });
    }
  }

  @Test
  void storeAliasesAreNotReportedAsMismatches() throws Exception {
    Path rtf = Fixtures.text(inputs, "letter.rtf", "{\\rtf1\\ansi Hello}");
    try (ConvertedPdf pdf = converter.toPdf(ConversionSource.of(rtf, "letter.rtf", "text/rtf"))) {
      assertThat(pdf.mediaType()).isEqualTo("application/rtf");
      assertThat(pdf.warnings()).isEmpty();
    }
  }

  @Test
  void unsupportedAndContradictoryContentIsRefusedAndLeavesNothingBehind() throws Exception {
    Path zip = Fixtures.zip(inputs);

    ConversionException unsupported = conversionFailure(
        ConversionSource.of(zip, "archive.zip", "application/zip"));
    assertThat(unsupported.reason()).isEqualTo(Reason.UNSUPPORTED_MEDIA_TYPE);
    assertThat(unsupported.getMessage()).contains("archive.zip", "application/zip");

    ConversionException conflict = conversionFailure(
        ConversionSource.of(zip, "claim.pdf", "application/pdf"));
    assertThat(conflict.reason()).isEqualTo(Reason.MEDIA_TYPE_CONFLICT);
    assertThat(conflict.getMessage()).contains("claim.pdf", "ZIP");

    ConversionException untyped = conversionFailure(
        ConversionSource.of(Fixtures.text(inputs, "notes", "plain words"), "notes"));
    assertThat(untyped.reason()).isEqualTo(Reason.UNSUPPORTED_MEDIA_TYPE);

    assertThat(workDirectories()).isEmpty();
  }

  @Test
  void brokenPdfsAreRefusedWithTheReason() throws Exception {
    ConversionException encrypted = conversionFailure(ConversionSource.of(
        Fixtures.encryptedPdf(inputs), "locked.pdf", "application/pdf"));
    assertThat(encrypted.reason()).isEqualTo(Reason.ENCRYPTED);
    assertThat(encrypted.getMessage()).contains("locked.pdf");

    Path truncated = inputs.resolve("truncated.pdf");
    Files.write(truncated, "%PDF-1.4\n1 0 obj << /Type /Catalog".getBytes());
    assertThat(conversionFailure(ConversionSource.of(truncated, "truncated.pdf",
        "application/pdf")).reason()).isEqualTo(Reason.INVALID_CONTENT);
    assertThat(workDirectories()).isEmpty();
  }

  @Test
  void officeFormatsNeedDocmosis() throws Exception {
    DocumentConverter withoutDocmosis = DocumentConverter.builder().tempDirectory(work).build();
    assertThat(withoutDocmosis.supportedMediaTypes())
        .contains("application/pdf", "image/tiff").doesNotContain(DOCX, "text/csv");

    ConversionException failure = catchThrowableOfType(ConversionException.class,
        () -> withoutDocmosis.toPrintReadyPdf(ConversionSource.of(
            Fixtures.real(inputs, "wordDocument2.docx"), "letter.docx", DOCX),
            PrintOptions.bulkPrint()));
    assertThat(failure.reason()).isEqualTo(Reason.CONVERTER_NOT_CONFIGURED);
    assertThat(failure.getMessage()).contains("letter.docx", "ccd.conversion.docmosis");
  }

  @Test
  void docmosisFailuresSayWhetherARetryCouldWork() throws Exception {
    Path docx = Fixtures.real(inputs, "wordDocument2.docx");
    for (boolean outage : List.of(true, false)) {
      DocumentConverter failing = DocumentConverter.builder().tempDirectory(work)
          .docmosis(StubDocmosis.failing(new DocmosisRenderException(
              "Docmosis convert of letter.docx failed with HTTP " + (outage ? 503 : 400),
              outage)))
          .build();
      ConversionException failure = catchThrowableOfType(ConversionException.class,
          () -> failing.toPdf(ConversionSource.of(docx, "letter.docx", DOCX)));
      assertThat(failure.reason()).isEqualTo(Reason.CONVERTER_FAILED);
      assertThat(failure.isTransientFailure()).isEqualTo(outage);
      assertThat(failure.getMessage()).contains("letter.docx", outage ? "503" : "400");
    }

    DocumentConverter garbage = DocumentConverter.builder().tempDirectory(work)
        .docmosis(StubDocmosis.returning("<html>oops</html>".getBytes(), inputs)).build();
    assertThat(catchThrowableOfType(ConversionException.class,
        () -> garbage.toPdf(ConversionSource.of(docx, "letter.docx", DOCX))).reason())
        .isEqualTo(Reason.INVALID_CONTENT);
    assertThat(workDirectories()).isEmpty();
  }

  @Test
  void closingTheResultDeletesItAndTheSourceIsUntouched() throws Exception {
    Path source = Fixtures.real(inputs, "flying-pig.jpg");
    byte[] before = Files.readAllBytes(source);
    Path output;
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(
        ConversionSource.of(source, "flying-pig.jpg", "image/jpeg"), PrintOptions.bulkPrint())) {
      output = pdf.file();
      assertThat(output).exists();
      assertThat(workDirectories()).hasSize(1);
    }
    assertThat(output).doesNotExist();
    assertThat(workDirectories()).isEmpty();
    assertThat(Files.readAllBytes(source)).isEqualTo(before);
  }

  @Test
  void theCheckerFlagsWhatTheConverterRemoves() throws Exception {
    // The source breaks the rules the converter fixes; if the checker could not see them, the
    // golden test's "no violations" would prove nothing.
    List<String> violations = PdfX1aChecker.violations(Fixtures.transparencyPdf(inputs));
    assertThat(violations).anyMatch(v -> v.contains("PDF 1.3"))
        .anyMatch(v -> v.contains("GTS_PDFXVersion"))
        .anyMatch(v -> v.contains("output intent"))
        .anyMatch(v -> v.contains("TrimBox"))
        .anyMatch(v -> v.contains("DeviceRGB"))
        .anyMatch(v -> v.contains("opaque"))
        .anyMatch(v -> v.contains("soft mask"));
    assertThat(PdfX1aChecker.violations(Fixtures.mixedLayoutPdf(inputs)))
        .anyMatch(v -> v.contains("DeviceRGB"));
  }

  @Test
  void theGoldenComparisonRejectsAMovedMargin() throws Exception {
    // The pixel tolerance must not be so loose that a layout regression slips through.
    Path source = Fixtures.real(inputs, "flying-pig.jpg");
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(
        ConversionSource.of(source, "flying-pig.jpg", "image/jpeg"),
        PrintOptions.builder().marginMillimetres(15).build())) {
      assertThat(Goldens.compare(Goldens.page("jpeg-photo", 1),
          PrintFacts.render(pdf.file(), Goldens.PREVIEW_DPI).get(0)).acceptable()).isFalse();
    }
  }

  /**
   * The output reaches Xerox exactly as converted only if send-letter neither pads nor merges it:
   * replay send-letter's own processing and require the bytes, and so PDF/X-1a, to survive.
   */
  @Test
  void sendLetterForwardsTheOutputUnchanged() throws Exception {
    Path coversheet = Files.write(inputs.resolve("coversheet.pdf"),
        Fixtures.pdfSaying("Coversheet"));
    try (ConvertedPdf photo = converter.toPrintReadyPdf(ConversionSource.of(
            Fixtures.real(inputs, "flying-pig.jpg"), "flying-pig.jpg", "image/jpeg"),
            PrintOptions.bulkPrint());
        ConvertedPdf letter = converter.toPrintReadyLetter("Claim pack", List.of(
            PrintPart.asIs(coversheet, "coversheet"),
            PrintPart.convert(ConversionSource.of(Fixtures.multiPageTiff(inputs), "scans.tiff",
                "image/tiff"))), PrintOptions.bulkPrint())) {
      for (ConvertedPdf pdf : List.of(photo, letter)) {
        byte[] sent = Files.readAllBytes(pdf.file());
        assertThat(SendLetterReplay.uploadedBytes(List.of(sent))).isEqualTo(sent);
      }
      assertThat(PdfX1aChecker.violations(photo.file())).isEmpty();
    }
  }

  /**
   * Send-letter drops a document whose page content streams match one already sent to the same
   * recipients. A retry of the same pack must match; a different pack with the same layout must
   * not, or it would silently never be posted.
   */
  @Test
  void sendLetterTellsDifferentPacksApartButRecognisesARetry() throws Exception {
    Path coversheet = Files.write(inputs.resolve("coversheet.pdf"),
        Fixtures.pdfSaying("Coversheet"));
    // Same size, same layout, different pictures.
    Path photo = Fixtures.bmp(Files.createDirectories(inputs.resolve("a")));
    Path otherPhoto = Fixtures.alphaPngSized(Files.createDirectories(inputs.resolve("b")),
        640, 480);

    String first = checksumOfLetter(coversheet, photo, "photo.bmp", "image/bmp");
    String retry = checksumOfLetter(coversheet, photo, "photo.bmp", "image/bmp");
    String other = checksumOfLetter(coversheet, otherPhoto, "alpha.png", "image/png");

    assertThat(retry).isEqualTo(first);
    assertThat(other).isNotEqualTo(first);
  }

  private String checksumOfLetter(Path coversheet, Path evidence, String name, String type)
      throws Exception {
    try (ConvertedPdf letter = converter.toPrintReadyLetter("Claim pack", List.of(
        PrintPart.asIs(coversheet, "coversheet"),
        PrintPart.convert(ConversionSource.of(evidence, name, type))),
        PrintOptions.bulkPrint())) {
      return SendLetterReplay.duplicateChecksum(Files.readAllBytes(letter.file()));
    }
  }

  @Test
  void colourDropsToGreyscaleNearTheSizeLimitAndFailsAboveIt() throws Exception {
    Path photo = Fixtures.largePhoto(inputs);
    ConversionSource source = ConversionSource.of(photo, "large-photo.jpg", "image/jpeg");
    long cmyk = size(source, PrintOptions.builder().maxOutputBytes(0).build());
    long grey = size(source, PrintOptions.builder().maxOutputBytes(0)
        .colour(PrintColour.GRAYSCALE).build());
    assertThat(grey).isLessThan(cmyk);

    // CMYK at half the limit: under 80%, kept in colour.
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(source,
        PrintOptions.builder().maxOutputBytes(cmyk * 2).build())) {
      assertThat(colourSpaces(pdf)).containsOnly("DeviceCMYK");
      assertThat(pdf.warnings()).isEmpty();
    }
    // CMYK at the limit: over 80%, redone in greyscale, which fits.
    PrintOptions tight = PrintOptions.builder().maxOutputBytes(cmyk).build();
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(source, tight)) {
      assertThat(colourSpaces(pdf)).containsOnly("DeviceGray");
      assertThat(Files.size(pdf.file())).isEqualTo(grey);
      assertThat(pdf.warnings()).singleElement().satisfies(warning -> {
        assertThat(warning.code()).isEqualTo(Code.COLOUR_REDUCED_FOR_SIZE);
        assertThat(warning.message()).contains("large-photo.jpg", "80%");
      });
      PrintRequirements.assertPrintReady(pdf.file(),
          tight.toBuilder().colour(PrintColour.GRAYSCALE).build());
    }
    // Too big even in greyscale.
    ConversionException failure = catchThrowableOfType(ConversionException.class,
        () -> converter.toPrintReadyPdf(source,
            PrintOptions.builder().maxOutputBytes(grey - 1).build()));
    assertThat(failure.reason()).isEqualTo(Reason.OUTPUT_TOO_LARGE);
    assertThat(failure.getMessage()).contains("greyscale", String.valueOf(grey - 1));
    assertThat(workDirectories()).isEmpty();
  }

  private long size(ConversionSource source, PrintOptions options) throws Exception {
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(source, options)) {
      return Files.size(pdf.file());
    }
  }

  private static List<String> colourSpaces(ConvertedPdf pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf.file().toFile())) {
      return PrintFacts.pages(document).stream()
          .flatMap(page -> page.images().stream())
          .map(PrintFacts.PlacedImage::colourSpace)
          .toList();
    }
  }

  @Test
  void optionsRejectLayoutsThatCannotPrint() {
    assertThat(catchThrowableOfType(IllegalArgumentException.class,
        () -> PrintOptions.builder().marginMillimetres(90).build()))
        .hasMessageContaining("90.0mm margin");
    assertThat(catchThrowableOfType(IllegalArgumentException.class,
        () -> PrintOptions.builder().resolutionDpi(1200).build()))
        .hasMessageContaining("resolutionDpi");
  }

  private ConversionException conversionFailure(ConversionSource source) {
    return catchThrowableOfType(ConversionException.class,
        () -> converter.toPrintReadyPdf(source, PrintOptions.bulkPrint()));
  }

  private List<Path> workDirectories() throws IOException {
    try (Stream<Path> files = Files.list(work)) {
      return files.filter(p -> p.getFileName().toString().startsWith("ccd-conversion-"))
          .toList();
    }
  }

  private Path sampleFor(String extension) throws IOException {
    Path dir = Files.createDirectories(inputs.resolve(extension));
    return switch (extension) {
      case "jpg", "jpeg" -> Fixtures.real(dir, "flying-pig.jpg");
      case "png" -> Fixtures.alphaPng(dir);
      case "bmp" -> Fixtures.bmp(dir);
      case "tif", "tiff" -> Fixtures.multiPageTiff(dir);
      case "pdf" -> Fixtures.real(dir, "one-page.pdf");
      case "doc", "dot", "xls", "xlt", "xla", "ppt", "pot", "pps", "ppa" ->
          Fixtures.real(dir, "wordDocument.doc");
      case "docx", "dotx", "xlsx", "xltx", "xlsb", "pptx", "potx", "ppsx" ->
          Fixtures.real(dir, "wordDocument2.docx");
      case "rtf" -> Fixtures.text(dir, "sample.rtf", "{\\rtf1\\ansi Hello}");
      case "csv" -> Fixtures.text(dir, "sample.csv", "date,amount\n2026-01-01,450.00\n");
      default -> Fixtures.text(dir, "sample.txt", "Supporting statement");
    };
  }
}
