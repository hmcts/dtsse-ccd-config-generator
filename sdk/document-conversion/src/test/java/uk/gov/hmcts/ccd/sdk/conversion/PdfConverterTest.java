package uk.gov.hmcts.ccd.sdk.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException.Reason;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionWarning.Code;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.PrintRequirements;

/**
 * A service adds a file type by registering a {@link PdfConverter}: the type then converts,
 * prints and fails exactly as the built-in types do, and the converter only writes a PDF.
 */
class PdfConverterTest {
  private static final String OUTLOOK = "application/vnd.ms-outlook";

  @TempDir
  Path inputs;

  @TempDir
  Path work;

  private final OutlookConverter outlook = new OutlookConverter();

  @Test
  void aRegisteredConverterAddsItsMediaTypes() throws Exception {
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work)
        .converter(outlook).build();
    assertThat(converter.supportedMediaTypes()).contains(OUTLOOK, "application/pdf");

    Path email = Fixtures.text(inputs, "email.msg", "Subject: rent arrears");
    try (ConvertedPdf pdf = converter.toPdf(
        ConversionSource.of(email, "email.msg", "Application/VND.ms-Outlook; charset=x"))) {
      assertThat(pdf.pageCount()).isEqualTo(1);
      assertThat(pdf.mediaType()).isEqualTo(OUTLOOK);
      assertThat(Files.readString(pdf.file(), StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }
    assertThat(outlook.calls).singleElement()
        .as("the converter sees the routed type, normalised").isEqualTo("email.msg " + OUTLOOK);
    assertThat(workDirectories()).isEmpty();
  }

  @Test
  void aRegisteredTypeComesOutPrintReady() throws Exception {
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work)
        .converter(outlook).build();
    Path email = Fixtures.text(inputs, "email.msg", "Subject: hearing date");

    try (ConvertedPdf pdf = converter.toPrintReadyPdf(
        ConversionSource.of(email, "email.msg", OUTLOOK), PrintOptions.bulkPrint())) {
      PrintRequirements.assertPrintReady(pdf.file(), PrintOptions.bulkPrint());
    }
    assertThat(workDirectories()).isEmpty();
  }

  /** As with Docmosis, the greyscale retry re-renders the converter's first PDF. */
  @Test
  void theGreyscaleRetryDoesNotRunTheConverterAgain() throws Exception {
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work)
        .converter(outlook).build();
    List<PrintPart> parts = List.of(
        PrintPart.convert(ConversionSource.of(
            Fixtures.text(inputs, "email.msg", "Subject: pack"), "email.msg", OUTLOOK)),
        PrintPart.convert(ConversionSource.of(Fixtures.largePhoto(inputs), "large-photo.jpg",
            "image/jpeg")));
    long cmyk;
    try (ConvertedPdf letter = converter.toPrintReadyLetter("Pack", parts,
        PrintOptions.builder().maxOutputBytes(0).build())) {
      cmyk = Files.size(letter.file());
      PrintRequirements.assertPrintReady(letter.file(), PrintOptions.bulkPrint());
    }
    outlook.calls.clear();

    try (ConvertedPdf letter = converter.toPrintReadyLetter("Pack", parts,
        PrintOptions.builder().maxOutputBytes(cmyk).build())) {
      assertThat(letter.warnings()).extracting(ConversionWarning::code)
          .containsExactly(Code.COLOUR_REDUCED_FOR_SIZE);
    }
    assertThat(outlook.calls).containsExactly("email.msg " + OUTLOOK);
    assertThat(workDirectories()).isEmpty();
  }

  @Test
  void aRegisteredConverterReplacesTheBuiltInForItsType() throws Exception {
    PdfConverter plainText = new PdfConverter() {
      @Override
      public Set<String> mediaTypes() {
        return Set.of("text/plain");
      }

      @Override
      public void convert(ConversionSource source, Path output, ConversionContext context)
          throws IOException {
        Files.write(output, Fixtures.pdfSaying("Rendered without Docmosis"));
      }
    };
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work)
        .converter(plainText).build();

    assertThat(converter.supportedMediaTypes()).contains("text/plain")
        .doesNotContain("application/msword");
    try (ConvertedPdf pdf = converter.toPdf(ConversionSource.of(
        Fixtures.text(inputs, "notes.txt", "hello"), "notes.txt", "text/plain"))) {
      assertThat(pdf.pageCount()).isEqualTo(1);
    }
  }

  @Test
  void twoConvertersCannotClaimTheSameType() {
    assertThatThrownBy(() -> DocumentConverter.builder()
        .converter(outlook).converter(new OutlookConverter()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'" + OUTLOOK + "'");
  }

  @Test
  void outputThatIsNotAUsablePdfFailsTheConversion() throws Exception {
    PdfConverter broken = converterFor(OUTLOOK,
        (source, output, context) -> Files.writeString(output, "<html>not a pdf</html>"));
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work)
        .converter(broken).build();
    Path email = Fixtures.text(inputs, "email.msg", "Subject: x");

    for (PrintOptions print : new PrintOptions[] {null, PrintOptions.bulkPrint()}) {
      ConversionException failure = catchThrowableOfType(ConversionException.class,
          () -> convert(converter, ConversionSource.of(email, "email.msg", OUTLOOK), print));
      assertThat(failure.reason()).isEqualTo(Reason.INVALID_CONTENT);
      assertThat(failure.getMessage())
          .contains("email.msg", "the PDF it was converted to is unusable");
      assertThat(failure.isTransientFailure()).isFalse();
    }
    assertThat(workDirectories()).isEmpty();
  }

  @Test
  void aConverterThatThrowsFailsWithoutLeakingItsMessage() throws Exception {
    PdfConverter throwing = converterFor(OUTLOOK, (source, output, context) -> {
      throw new IllegalStateException("token=abc123");
    });
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work)
        .converter(throwing).build();

    ConversionException failure = catchThrowableOfType(ConversionException.class,
        () -> converter.toPdf(ConversionSource.of(
            Fixtures.text(inputs, "email.msg", "x"), "email.msg", OUTLOOK)));
    assertThat(failure.reason()).isEqualTo(Reason.INVALID_CONTENT);
    assertThat(failure.getMessage()).contains("IllegalStateException").doesNotContain("abc123");
    assertThat(workDirectories()).isEmpty();
  }

  @Test
  void aConverterIOFailureIsWorthRetrying() throws Exception {
    PdfConverter flaky = converterFor(OUTLOOK, (source, output, context) -> {
      throw new IOException("disk full");
    });
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work)
        .converter(flaky).build();

    ConversionException failure = catchThrowableOfType(ConversionException.class,
        () -> converter.toPdf(ConversionSource.of(
            Fixtures.text(inputs, "email.msg", "x"), "email.msg", OUTLOOK)));
    assertThat(failure.reason()).isEqualTo(Reason.IO_FAILURE);
    assertThat(failure.isTransientFailure()).isTrue();
  }

  @Test
  void scratchFilesLiveInTheConversionDirectoryAndGoWithIt() throws Exception {
    List<Path> scratch = new CopyOnWriteArrayList<>();
    PdfConverter usesScratch = converterFor(OUTLOOK, (source, output, context) -> {
      Path temp = context.createTempFile(".html");
      scratch.add(temp);
      Files.write(output, Fixtures.pdfSaying("from scratch"));
    });
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work)
        .converter(usesScratch).build();

    try (ConvertedPdf pdf = converter.toPdf(ConversionSource.of(
        Fixtures.text(inputs, "email.msg", "x"), "email.msg", OUTLOOK))) {
      assertThat(scratch).singleElement().satisfies(file -> {
        assertThat(file).exists();
        assertThat(file.startsWith(work)).isTrue();
      });
    }
    assertThat(scratch.getFirst()).doesNotExist();
    assertThat(workDirectories()).isEmpty();
  }

  @Test
  void anUnregisteredTypeIsUnsupportedAndNamesTheRoutedType() throws Exception {
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(work).build();

    ConversionException failure = catchThrowableOfType(ConversionException.class,
        () -> converter.toPdf(ConversionSource.of(
            Fixtures.text(inputs, "email.msg", "x"), "email.msg", OUTLOOK)));
    assertThat(failure.reason()).isEqualTo(Reason.UNSUPPORTED_MEDIA_TYPE);
    assertThat(failure.mediaType()).contains(OUTLOOK);
    assertThat(failure.getMessage()).contains("email.msg", OUTLOOK, "application/pdf");
  }

  private static ConvertedPdf convert(DocumentConverter converter, ConversionSource source,
      PrintOptions print) throws ConversionException {
    return print == null ? converter.toPdf(source) : converter.toPrintReadyPdf(source, print);
  }

  private interface Body {
    void convert(ConversionSource source, Path output, ConversionContext context)
        throws ConversionException, IOException;
  }

  private static PdfConverter converterFor(String type, Body body) {
    return new PdfConverter() {
      @Override
      public Set<String> mediaTypes() {
        return Set.of(type);
      }

      @Override
      public void convert(ConversionSource source, Path output, ConversionContext context)
          throws ConversionException, IOException {
        body.convert(source, output, context);
      }
    };
  }

  /** Stands in for a real e-mail converter: one page carrying the message's first line. */
  private static final class OutlookConverter implements PdfConverter {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public Set<String> mediaTypes() {
      return Set.of(OUTLOOK);
    }

    @Override
    public void convert(ConversionSource source, Path output, ConversionContext context)
        throws IOException {
      calls.add(source.fileName() + " " + source.mediaType());
      String firstLine = Files.readAllLines(source.file()).getFirst();
      Files.write(output, Fixtures.pdfSaying(firstLine));
    }
  }

  private List<Path> workDirectories() throws IOException {
    try (Stream<Path> files = Files.list(work)) {
      return files.filter(p -> p.getFileName().toString().startsWith("ccd-conversion-"))
          .toList();
    }
  }
}
