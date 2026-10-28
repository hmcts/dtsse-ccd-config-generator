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
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException.Reason;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionWarning.Code;
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
  @CsvSource({
      "jpg, image/jpeg", "jpeg, image/jpeg", "bmp, image/bmp", "tif, image/tiff",
      "tiff, image/tiff", "png, image/png", "pdf, application/pdf",
      "doc, application/msword", "dot, application/msword",
      "docx, application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "dotx, application/vnd.openxmlformats-officedocument.wordprocessingml.template",
      "xls, application/vnd.ms-excel", "xlt, application/vnd.ms-excel",
      "xla, application/vnd.ms-excel",
      "xlsx, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "xltx, application/vnd.openxmlformats-officedocument.spreadsheetml.template",
      "xlsb, application/vnd.ms-excel.sheet.binary.macroenabled.12",
      "ppt, application/vnd.ms-powerpoint", "pot, application/vnd.ms-powerpoint",
      "pps, application/vnd.ms-powerpoint", "ppa, application/vnd.ms-powerpoint",
      "pptx, application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "potx, application/vnd.openxmlformats-officedocument.presentationml.template",
      "ppsx, application/vnd.openxmlformats-officedocument.presentationml.slideshow",
      "txt, text/plain", "rtf, application/rtf", "csv, text/csv"})
  void everyPossessionsUploadTypeConvertsForPrint(String extension, String expectedType)
      throws Exception {
    Path file = sampleFor(extension);

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
    try (PDDocument document = Loader.loadPDF(Fixtures.mixedLayoutPdf(inputs).toFile())) {
      document.getDocumentCatalog().setVersion("1.7");
      Path overridden = inputs.resolve("catalog-version.pdf");
      document.save(overridden.toFile());
      assertThat(PdfX1aChecker.violations(overridden)).anyMatch(v -> v.contains("/Version 1.7"));
    }
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

    // CMYK at 79% of the limit: kept in colour.
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(source,
        PrintOptions.builder().maxOutputBytes((long) (cmyk / 0.79)).build())) {
      assertThat(colourSpaces(pdf)).containsOnly("DeviceCMYK");
      assertThat(pdf.warnings()).isEmpty();
    }
    // CMYK at 81% of the limit: redone in greyscale, which fits.
    PrintOptions tight = PrintOptions.builder().maxOutputBytes((long) (cmyk / 0.81)).build();
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

  /**
   * Phones store most photos sideways and record how to turn them in the EXIF orientation. The
   * printed photo must be the way up the person who took it saw it: portrait, with the dark
   * quadrant top left. Landscape pages are not turned here, so a sideways photo cannot come out
   * right by accident.
   */
  @ParameterizedTest(name = "orientation {0}")
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
  void phonePhotosPrintTheWayUpTheyWereTaken(int orientation) throws Exception {
    assertPrintsUpright(Fixtures.phonePhoto(inputs, orientation), "photo.jpg", "image/jpeg");
  }

  /** JPEG allows 0xFF fill bytes before a marker; the orientation must still be found. */
  @Test
  void anOrientationAfterFillBytesIsStillApplied() throws Exception {
    assertPrintsUpright(Fixtures.phonePhoto(inputs, 6, true), "photo.jpg", "image/jpeg");
  }

  /**
   * A TIFF page carries its own orientation tag, and a scanner may add a thumbnail of the page
   * as a second image: the page prints upright, and the thumbnail is not printed as a page.
   */
  @Test
  void aTiffPrintsUprightWithoutItsThumbnail() throws Exception {
    Path scan = Fixtures.tiffWithThumbnail(inputs, 6);
    assertPrintsUpright(scan, "scan.tif", "image/tiff");
    try (ConvertedPdf pdf = converter.toPdf(ConversionSource.of(scan, "scan.tif", "image/tiff"))) {
      assertThat(pdf.pageCount()).as("plain conversion pages").isEqualTo(1);
    }
  }

  private void assertPrintsUpright(Path image, String name, String type) throws Exception {
    PrintOptions options = PrintOptions.bulkPrint().toBuilder()
        .rotateLandscapePages(false).padToEvenPages(false).build();
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(ConversionSource.of(image, name, type),
        options)) {
      assertThat(pdf.pageCount()).isEqualTo(1);
      PrintRequirements.assertPrintReady(pdf.file(), options);
      PrintFacts.PlacedImage placed;
      try (PDDocument document = Loader.loadPDF(pdf.file().toFile())) {
        placed = PrintFacts.pages(document).get(0).images().get(0);
      }
      assertThat(placed.pixelHeight()).as("portrait").isGreaterThan(placed.pixelWidth());
      java.awt.image.BufferedImage page = PrintFacts.render(pdf.file(), 50).get(0);
      double pxPerMm = page.getWidth() / options.pageSize().widthMillimetres();
      double left = placed.leftMm();
      double width = options.pageSize().widthMillimetres() - placed.leftMm() - placed.rightMm();
      double top = placed.topMm();
      double height = options.pageSize().heightMillimetres() - placed.topMm() - placed.bottomMm();
      int[] quadrants = new int[4];
      for (int q = 0; q < 4; q++) {
        int x = (int) ((left + width * (q % 2 == 0 ? 0.25 : 0.75)) * pxPerMm);
        int y = (int) ((top + height * (q < 2 ? 0.25 : 0.75)) * pxPerMm);
        quadrants[q] = luminance(page.getRGB(x, y));
      }
      assertThat(quadrants[0] + 80).as("top left is the dark quadrant: %s",
          java.util.Arrays.toString(quadrants))
          .isLessThan(Math.min(quadrants[1], Math.min(quadrants[2], quadrants[3])));
    }
  }

  private static int luminance(int rgb) {
    return (299 * (rgb >> 16 & 0xFF) + 587 * (rgb >> 8 & 0xFF) + 114 * (rgb & 0xFF)) / 1000;
  }

  /**
   * A type inferred from the file name is the caller's best guess, not a declaration: when the
   * content disagrees, there is one warning, and neither it nor a refusal claims the caller
   * declared anything.
   */
  @Test
  void anInferredTypeIsNeverReportedAsDeclared() throws Exception {
    Path png = Fixtures.alphaPng(inputs);
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(
        ConversionSource.of(png, "photo.jpg", "application/octet-stream"),
        PrintOptions.bulkPrint())) {
      assertThat(pdf.mediaType()).isEqualTo("image/png");
      assertThat(pdf.warnings()).singleElement().satisfies(warning -> {
        assertThat(warning.code()).isEqualTo(Code.MEDIA_TYPE_INFERRED);
        assertThat(warning.message())
            .contains("no usable media type", "suggests 'image/jpeg'", "content is 'image/png'")
            .doesNotContain("declared");
      });
    }
    ConversionException refused = catchThrowableOfType(ConversionException.class,
        () -> converter.toPdf(ConversionSource.of(Fixtures.zip(inputs), "bundle.pdf", null)));
    assertThat(refused.reason()).isEqualTo(Reason.MEDIA_TYPE_CONFLICT);
    assertThat(refused.getMessage())
        .contains("no usable media type", "suggests 'application/pdf'", "ZIP container")
        .doesNotContain("declared");
  }

  /** Text that happens to start with "BM" is text, not a bitmap. */
  @Test
  void textStartingWithBmIsNotMistakenForABitmap() throws Exception {
    Path text = Fixtures.text(inputs, "notes.txt", "BMW service history\n2019: new tyres\n");
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(
        ConversionSource.of(text, "notes.txt", "text/plain"), PrintOptions.bulkPrint())) {
      assertThat(pdf.mediaType()).isEqualTo("text/plain");
      assertThat(pdf.warnings()).isEmpty();
    }
    assertThat(docmosis.calls()).containsExactly(new StubDocmosis.Call("notes.txt", "text/plain"));
  }

  /**
   * The greyscale retry re-renders what Docmosis returned the first time: asking again would
   * double the load on Docmosis and let a blip there fail a letter that had already converted.
   * A letter made only of converted parts is identified as PDF/X-1a.
   */
  @Test
  void theGreyscaleRetryDoesNotCallDocmosisAgain() throws Exception {
    Path docx = Fixtures.text(inputs, "statement.docx", "stand-in for a Word document");
    List<PrintPart> parts = List.of(
        PrintPart.convert(ConversionSource.of(docx, "statement.docx", DOCX)),
        PrintPart.convert(ConversionSource.of(Fixtures.largePhoto(inputs), "large-photo.jpg",
            "image/jpeg")));
    long cmyk;
    try (ConvertedPdf letter = converter.toPrintReadyLetter("Pack", parts,
        PrintOptions.builder().maxOutputBytes(0).build())) {
      cmyk = Files.size(letter.file());
      PrintRequirements.assertPrintReady(letter.file(), PrintOptions.bulkPrint());
    }
    docmosis.calls().clear();

    PrintOptions tight = PrintOptions.builder().maxOutputBytes(cmyk).build();
    try (ConvertedPdf letter = converter.toPrintReadyLetter("Pack", parts, tight)) {
      assertThat(colourSpaces(letter)).containsOnly("DeviceGray");
      assertThat(letter.warnings()).extracting(ConversionWarning::code)
          .containsExactly(Code.COLOUR_REDUCED_FOR_SIZE);
    }
    assertThat(docmosis.calls()).containsExactly(new StubDocmosis.Call("statement.docx", DOCX));
    assertThat(workDirectories()).isEmpty();
  }

  /**
   * The separation puts each colour on the plates a printer expects: pure red is magenta and
   * yellow, black and grey are on the black plate alone, never a four-colour rich black.
   */
  @Test
  void coloursSeparateOntoTheExpectedPlates() throws Exception {
    try (ConvertedPdf pdf = converter.toPrintReadyPdf(ConversionSource.of(
        Fixtures.colourSwatch(inputs), "swatch.png", "image/png"), PrintOptions.bulkPrint());
        PDDocument document = Loader.loadPDF(pdf.file().toFile())) {
      PDResources resources = document.getPage(0).getResources();
      PDImageXObject image = (PDImageXObject) resources.getXObject(
          resources.getXObjectNames().iterator().next());
      assertThat(image.getColorSpace().getName()).isEqualTo("DeviceCMYK");
      byte[] samples;
      try (java.io.InputStream in = image.getStream().createInputStream()) {
        samples = in.readAllBytes();
      }
      int[][] expected = {
          {0, 255, 255, 0}, {255, 0, 255, 0}, {255, 255, 0, 0}, {0, 0, 0, 255}, {0, 0, 0, 127}};
      for (int band = 0; band < expected.length; band++) {
        int y = image.getHeight() * (2 * band + 1) / 10;
        int at = (y * image.getWidth() + image.getWidth() / 2) * 4;
        int[] cmyk = {samples[at] & 0xFF, samples[at + 1] & 0xFF, samples[at + 2] & 0xFF,
            samples[at + 3] & 0xFF};
        for (int plate = 0; plate < 4; plate++) {
          assertThat(cmyk[plate]).as("band %d plate %d of %s", band, plate,
              java.util.Arrays.toString(cmyk)).isCloseTo(expected[band][plate],
              org.assertj.core.data.Offset.offset(2));
        }
      }
    }
  }

  /**
   * A converter would turn any bytes it is given into pages of text, so binary content that is
   * declared, or only named, as text is refused rather than printed as garbage.
   */
  @Test
  void binaryContentPassedOffAsTextIsRefused() throws Exception {
    Path elf = Files.write(inputs.resolve("data.csv"),
        new byte[] {0x7F, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0x3E, 0});
    for (ConversionSource source : List.of(
        ConversionSource.of(Fixtures.zip(inputs), "archive.txt", "text/plain"),
        ConversionSource.of(elf, "data.csv", "application/octet-stream"))) {
      ConversionException refused = catchThrowableOfType(ConversionException.class,
          () -> converter.toPrintReadyPdf(source, PrintOptions.bulkPrint()));
      assertThat(refused.reason()).as(source.fileName()).isEqualTo(Reason.MEDIA_TYPE_CONFLICT);
    }
    assertThat(docmosis.calls()).isEmpty();
    assertThat(workDirectories()).isEmpty();
  }

  /** Running out of memory on a huge document must not leave its files behind. */
  @Test
  void anErrorStillCleansUp() throws Exception {
    DocumentConverter failing = DocumentConverter.builder()
        .docmosis((source, fileName, mediaType) -> {
          throw new OutOfMemoryError("simulated");
        })
        .tempDirectory(work).build();
    Path docx = Fixtures.text(inputs, "statement.docx", "stand-in for a Word document");
    assertThat(catchThrowableOfType(OutOfMemoryError.class, () -> failing.toPrintReadyPdf(
        ConversionSource.of(docx, "statement.docx", DOCX), PrintOptions.bulkPrint())))
        .isNotNull();
    assertThat(catchThrowableOfType(OutOfMemoryError.class, () -> failing.toPdf(
        ConversionSource.of(docx, "statement.docx", DOCX)))).isNotNull();
    assertThat(workDirectories()).isEmpty();
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
