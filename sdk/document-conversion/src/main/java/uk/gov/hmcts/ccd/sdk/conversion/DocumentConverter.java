package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException.Reason;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionWarning.Code;
import uk.gov.hmcts.ccd.sdk.conversion.detect.MediaTypeDetection;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderService;

/**
 * Converts documents to PDF, optionally laid out for print.
 *
 * <pre>{@code
 * DocumentConverter converter = DocumentConverter.builder()
 *     .docmosis(docmosisRenderService)   // needed for Word, Excel, PowerPoint, text, RTF, CSV
 *     .build();
 *
 * try (ConvertedPdf pdf = converter.toPrintReadyPdf(
 *     ConversionSource.of(file, "evidence.docx", declaredMediaType), PrintOptions.bulkPrint())) {
 *   sendToBulkPrint(pdf.open());
 * }
 * }</pre>
 *
 * <p>The declared media type is checked against the content first. Content that is recognisably
 * another type is converted as that type, with a {@link ConversionWarning}; a missing or
 * {@code application/octet-stream} type is inferred from the file name. The converter is
 * thread-safe and holds no state between calls.
 */
public final class DocumentConverter {
  private static final Set<String> UNTYPED =
      Set.of("", "application/octet-stream", "binary/octet-stream");

  private final DocmosisRenderService docmosis;
  private final Path tempDirectory;

  private DocumentConverter(DocmosisRenderService docmosis, Path tempDirectory) {
    this.docmosis = docmosis;
    this.tempDirectory = tempDirectory;
  }

  /**
   * A builder with no Docmosis client and {@code java.io.tmpdir} as the temp directory.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** The media types this converter can convert, given whether Docmosis is configured. */
  public Set<String> supportedMediaTypes() {
    Set<String> types = new TreeSet<>();
    types.add(SupportedMediaTypes.PDF);
    types.addAll(SupportedMediaTypes.IMAGES);
    if (convertsOffice()) {
      types.addAll(SupportedMediaTypes.OFFICE);
    }
    return Collections.unmodifiableSet(types);
  }

  /**
   * Converts to PDF without changing the layout: a PDF is validated and copied, an image is
   * centred on a Letter page, and an office document is whatever Docmosis returns.
   */
  public ConvertedPdf toPdf(ConversionSource source) throws ConversionException {
    return convert(Objects.requireNonNull(source, "source must be provided"), null);
  }

  /**
   * Converts to a PDF laid out and encoded for print: see {@link PrintOptions} for what the
   * output guarantees. Text is not selectable in the output, because every page is rendered to
   * an image.
   */
  public ConvertedPdf toPrintReadyPdf(ConversionSource source, PrintOptions options)
      throws ConversionException {
    Objects.requireNonNull(source, "source must be provided");
    return toPrintReadyLetter(source.fileName(), List.of(PrintPart.convert(source)), options);
  }

  /**
   * Builds one print-ready PDF from several parts, in order: {@link PrintPart#convert} parts
   * are converted as {@link #toPrintReadyPdf} does, {@link PrintPart#asIs} parts (a coversheet)
   * keep their pages unchanged. The letter is padded to an even page count when
   * {@link PrintOptions#padToEvenPages()} is set, and the size limit applies to the whole
   * letter: if CMYK output passes {@link PrintOptions#greyscaleAboveFraction()} of it, every
   * converted part is redone in greyscale.
   *
   * @param title the document title written to the PDF
   */
  public ConvertedPdf toPrintReadyLetter(String title, List<PrintPart> parts,
      PrintOptions options) throws ConversionException {
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title must be provided");
    }
    Objects.requireNonNull(options, "options must be provided");
    if (Objects.requireNonNull(parts, "parts must be provided").isEmpty()) {
      throw new IllegalArgumentException("parts must not be empty");
    }
    Path work = createWorkDirectory();
    try {
      Assembled letter = assemble(title, parts, options, Files.createDirectory(
          work.resolve("attempt-1")));
      List<ConversionWarning> warnings = new ArrayList<>(letter.warnings());
      long threshold = options.greyscaleThresholdBytes();
      if (options.colour() == PrintColour.CMYK && threshold > 0 && letter.size() > threshold) {
        final long cmykSize = letter.size();
        warnings = new ArrayList<>();
        PrintOptions grey = options.toBuilder().colour(PrintColour.GRAYSCALE).build();
        WorkDirectory.deleteRecursively(letter.file().getParent());
        letter = assemble(title, parts, grey, Files.createDirectory(work.resolve("attempt-2")));
        warnings.addAll(letter.warnings());
        warnings.add(new ConversionWarning(Code.COLOUR_REDUCED_FOR_SIZE,
            title + " was " + cmykSize + " bytes in CMYK, above " + threshold + " bytes ("
                + Math.round(options.greyscaleAboveFraction() * 100) + "% of the "
                + options.maxOutputBytes() + " byte limit); it was converted in greyscale ("
                + letter.size() + " bytes)."));
      }
      if (options.maxOutputBytes() > 0 && letter.size() > options.maxOutputBytes()) {
        throw new ConversionException(Reason.OUTPUT_TOO_LARGE,
            title + " is " + letter.size() + " bytes as a print-ready PDF, above the "
                + options.maxOutputBytes() + " byte limit even in " + (letter.options().colour()
                == PrintColour.GRAYSCALE ? "greyscale" : "CMYK") + ".");
      }
      return new ConvertedPdf(letter.file(), work, letter.pages(), letter.mediaType(), warnings);
    } catch (ConversionException e) {
      WorkDirectory.deleteRecursively(work);
      throw e;
    } catch (IOException e) {
      WorkDirectory.deleteRecursively(work);
      throw new ConversionException(Reason.IO_FAILURE,
          "Local I/O failed while converting " + title, e);
    } catch (RuntimeException e) {
      WorkDirectory.deleteRecursively(work);
      throw new ConversionException(Reason.INVALID_CONTENT,
          title + " could not be converted: " + e.getClass().getSimpleName(), e);
    }
  }

  private record Assembled(Path file, int pages, long size, String mediaType,
      List<ConversionWarning> warnings, PrintOptions options) {
  }

  private Assembled assemble(String title, List<PrintPart> parts, PrintOptions options,
      Path dir) throws ConversionException, IOException {
    List<ConversionWarning> warnings = new ArrayList<>();
    List<Path> pieces = new ArrayList<>();
    String mediaType = SupportedMediaTypes.PDF;
    int pages = 0;
    for (PrintPart part : parts) {
      switch (part) {
        case PrintPart.Convert convert -> {
          Path piece = WorkDirectory.createFile(dir, ".pdf");
          Converted converted = convertInto(convert.source(), options, dir, piece, warnings);
          pieces.add(piece);
          pages = converted.pages();
          mediaType = parts.size() == 1 ? converted.mediaType() : SupportedMediaTypes.PDF;
        }
        case PrintPart.AsIs asIs -> {
          try {
            PdfChecks.requireUsablePdf(asIs.pdf(), dir);
          } catch (ConversionException e) {
            throw new ConversionException(e.reason(), asIs.name() + ": " + e.getMessage(), e);
          }
          pieces.add(asIs.pdf());
        }
      }
    }
    boolean alreadyFinal = parts.size() == 1 && parts.get(0) instanceof PrintPart.Convert
        && (!options.padToEvenPages() || pages % 2 == 0);
    Path output = pieces.get(0);
    if (!alreadyFinal) {
      output = WorkDirectory.createFile(dir, ".pdf");
      pages = PrintAssembler.join(pieces, title, options, dir, output);
    }
    return new Assembled(output, pages, Files.size(output), mediaType, warnings, options);
  }

  private ConvertedPdf convert(ConversionSource source, PrintOptions print)
      throws ConversionException {
    Path work = createWorkDirectory();
    try {
      List<ConversionWarning> warnings = new ArrayList<>();
      Path output = WorkDirectory.createFile(work, ".pdf");
      Converted converted = convertInto(source, print, work, output, warnings);
      return new ConvertedPdf(output, work, converted.pages(), converted.mediaType(), warnings);
    } catch (ConversionException e) {
      WorkDirectory.deleteRecursively(work);
      throw e;
    } catch (IOException e) {
      WorkDirectory.deleteRecursively(work);
      throw new ConversionException(Reason.IO_FAILURE,
          "Local I/O failed while converting " + source.fileName(), e);
    } catch (RuntimeException e) {
      WorkDirectory.deleteRecursively(work);
      throw new ConversionException(Reason.INVALID_CONTENT,
          source.fileName() + " could not be converted: " + e.getClass().getSimpleName(), e);
    }
  }

  private record Converted(int pages, String mediaType) {
  }

  /**
   * Converts one source into {@code output}; {@code print} is null for plain conversion.
   */
  private Converted convertInto(ConversionSource source, PrintOptions print, Path work,
      Path output, List<ConversionWarning> warnings) throws ConversionException, IOException {
    String type = effectiveType(source, warnings);
    int pages;
    if (SupportedMediaTypes.IMAGES.contains(type)) {
      pages = convertImage(source, print, work, output);
    } else if (SupportedMediaTypes.PDF.equals(type)) {
      pages = convertPdf(source, source.file(), print, work, output);
    } else if (SupportedMediaTypes.OFFICE.contains(type)) {
      Path converted = viaDocmosis(source, type, work);
      pages = convertPdf(source, converted, print, work, output);
      if (print != null) {
        Files.deleteIfExists(converted);
      }
    } else {
      throw new ConversionException(Reason.UNSUPPORTED_MEDIA_TYPE,
          source.fileName() + " is '" + (type.isEmpty() ? "of no known type" : type)
              + "', which cannot be converted. Supported types: " + supportedMediaTypes());
    }
    return new Converted(pages, type);
  }

  private Path createWorkDirectory() throws ConversionException {
    try {
      return WorkDirectory.create(tempDirectory);
    } catch (IOException e) {
      throw new ConversionException(Reason.IO_FAILURE,
          "Could not create a working directory under " + tempDirectory, e);
    }
  }

  private static int convertImage(ConversionSource source, PrintOptions print, Path work,
      Path output) throws ConversionException {
    if (print == null) {
      return ImageToPdf.write(source.file(), output);
    }
    try {
      return new PrintRenderer(print, work).renderImage(source.file(), source.fileName(), output);
    } catch (IOException e) {
      throw new ConversionException(Reason.INVALID_CONTENT,
          source.fileName() + ": the image could not be decoded", e);
    }
  }

  private static int convertPdf(ConversionSource source, Path pdf, PrintOptions print,
      Path work, Path output) throws ConversionException, IOException {
    int pages;
    try {
      pages = PdfChecks.requireUsablePdf(pdf, work);
    } catch (ConversionException e) {
      throw new ConversionException(e.reason(), source.fileName() + ": " + e.getMessage(), e);
    }
    if (print == null) {
      if (pdf.equals(source.file())) {
        Files.copy(pdf, output, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.move(pdf, output, StandardCopyOption.REPLACE_EXISTING);
      }
      return pages;
    }
    return new PrintRenderer(print, work).renderPdf(pdf, source.fileName(), output);
  }

  private Path viaDocmosis(ConversionSource source, String type, Path work)
      throws ConversionException, IOException {
    if (!convertsOffice()) {
      throw new ConversionException(Reason.CONVERTER_NOT_CONFIGURED,
          source.fileName() + " is '" + type + "', which needs Docmosis, and no Docmosis "
              + "client is configured. Call docmosis(...) on the converter builder, or set "
              + "ccd.conversion.docmosis.convert-endpoint and access-key.");
    }
    Path converted;
    try {
      converted = docmosis.convertToPdf(source.file(), source.fileName(), type);
    } catch (DocmosisRenderException e) {
      throw new ConversionException(Reason.CONVERTER_FAILED,
          "Docmosis could not convert " + source.fileName() + ": " + e.getMessage(),
          e.isTransientFailure(), e);
    }
    // Move the result into this conversion's directory so closing the result cleans it up.
    Path owned = WorkDirectory.createFile(work, ".docmosis.pdf");
    try {
      Files.move(converted, owned, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.deleteIfExists(converted);
      throw e;
    }
    return owned;
  }

  private boolean convertsOffice() {
    return docmosis != null && docmosis.convertsFiles();
  }

  private static String effectiveType(ConversionSource source, List<ConversionWarning> warnings)
      throws ConversionException {
    String declared = alias(MediaTypeDetection.normalise(source.mediaType()));
    boolean untyped = UNTYPED.contains(declared);
    if (untyped) {
      Optional<String> inferred = MediaTypeDetection.fromFileName(source.fileName());
      if (inferred.isPresent()) {
        declared = inferred.get();
        warnings.add(new ConversionWarning(Code.MEDIA_TYPE_INFERRED,
            source.fileName() + " has no usable media type; '" + declared
                + "' was inferred from its file name."));
      }
    }
    byte[] head = new byte[MediaTypeDetection.DETECTION_PREFIX_BYTES];
    int length;
    try (InputStream in = Files.newInputStream(source.file())) {
      length = in.readNBytes(head, 0, head.length);
    } catch (IOException e) {
      throw new ConversionException(Reason.IO_FAILURE,
          "Could not read " + source.fileName(), e);
    }
    return switch (MediaTypeDetection.route(declared, head, length)) {
      case MediaTypeDetection.Routing.Route route -> route.mediaType();
      case MediaTypeDetection.Routing.RouteWithMismatch mismatch -> {
        warnings.add(UNTYPED.contains(mismatch.declared())
            ? new ConversionWarning(Code.MEDIA_TYPE_INFERRED,
                source.fileName() + " has no usable media type; its content is '"
                    + mismatch.detected() + "'.")
            : new ConversionWarning(Code.MEDIA_TYPE_MISMATCH,
                source.fileName() + " was declared '" + mismatch.declared()
                    + "' but its content is '" + mismatch.detected()
                    + "'; it was converted as '" + mismatch.mediaType() + "'."));
        yield mismatch.mediaType();
      }
      case MediaTypeDetection.Routing.Irreconcilable conflict ->
          throw new ConversionException(Reason.MEDIA_TYPE_CONFLICT,
              source.fileName() + " was declared '" + conflict.declared() + "' but is "
                  + conflict.detectedDescription() + ".");
    };
  }

  private static String alias(String type) {
    return SupportedMediaTypes.ALIASES.getOrDefault(type, type);
  }

  /**
   * Builds a {@link DocumentConverter}.
   */
  public static final class Builder {
    private DocmosisRenderService docmosis;
    private Path tempDirectory;

    private Builder() {
    }

    /** The Docmosis client used for office formats. Without it they are unsupported. */
    public Builder docmosis(DocmosisRenderService docmosis) {
      this.docmosis = Objects.requireNonNull(docmosis, "docmosis must be provided");
      return this;
    }

    /**
     * Where each conversion creates its private working directory, which also holds the result
     * until it is closed. Defaults to {@code java.io.tmpdir}.
     */
    public Builder tempDirectory(Path tempDirectory) {
      this.tempDirectory = Objects.requireNonNull(tempDirectory, "tempDirectory must be provided");
      return this;
    }

    /** Builds the converter. */
    public DocumentConverter build() {
      return new DocumentConverter(docmosis,
          tempDirectory != null ? tempDirectory : Path.of(System.getProperty("java.io.tmpdir")));
    }
  }
}
