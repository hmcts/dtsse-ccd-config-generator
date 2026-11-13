package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException.Reason;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionWarning.Code;
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
 * {@code application/octet-stream} type is inferred from the file name. The source is then
 * handed to the {@link PdfConverter} registered for its type: the built-in ones convert PDF,
 * images and, with Docmosis, office formats, and {@link Builder#converter} adds more. The
 * converter is thread-safe and holds no state between calls.
 */
public final class DocumentConverter {
  private static final Logger log = LoggerFactory.getLogger(DocumentConverter.class);
  private static final Set<String> UNTYPED =
      Set.of("", "application/octet-stream", "binary/octet-stream");
  /** Protects the host's disk from a converter that allocates without limit. */
  static final int MAX_TEMP_FILES_PER_CONVERSION = 100;

  private final Map<String, PdfConverter> converters;
  private final boolean docmosisConverts;
  private final Path tempDirectory;

  private DocumentConverter(Map<String, PdfConverter> converters, boolean docmosisConverts,
      Path tempDirectory) {
    this.converters = Map.copyOf(converters);
    this.docmosisConverts = docmosisConverts;
    this.tempDirectory = tempDirectory;
  }

  /**
   * A builder with the built-in converters, no Docmosis client and {@code java.io.tmpdir} as the
   * temp directory.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** The media types this converter can convert: the built-in ones and any registered. */
  public Set<String> supportedMediaTypes() {
    return Collections.unmodifiableSet(new TreeSet<>(converters.keySet()));
  }

  /**
   * Converts to PDF without changing the layout: a PDF is validated and copied, an image is
   * centred on a Letter page, an office document is whatever Docmosis returns, and any other
   * type is whatever its registered {@link PdfConverter} writes.
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
    boolean succeeded = false;
    try {
      // A converter's PDF is the same whatever the colour, so a greyscale retry reuses it.
      Intermediates intermediates = new Intermediates(work, new IdentityHashMap<>());
      Assembled letter = assemble(title, parts, options, intermediates, Files.createDirectory(
          work.resolve("attempt-1")));
      List<ConversionWarning> warnings = new ArrayList<>(letter.warnings());
      long threshold = options.greyscaleThresholdBytes();
      if (options.colour() == PrintColour.CMYK && threshold > 0 && letter.size() > threshold) {
        final long cmykSize = letter.size();
        warnings = new ArrayList<>();
        PrintOptions grey = options.toBuilder().colour(PrintColour.GRAYSCALE).build();
        WorkDirectory.deleteRecursively(letter.file().getParent());
        letter = assemble(title, parts, grey, intermediates,
            Files.createDirectory(work.resolve("attempt-2")));
        warnings.addAll(letter.warnings());
        warnings.add(new ConversionWarning(Code.COLOUR_REDUCED_FOR_SIZE,
            title + " was " + cmykSize + " bytes in CMYK, above " + threshold + " bytes ("
                + Math.round(options.greyscaleAboveFraction() * 100) + "% of the "
                + options.maxOutputBytes() + " byte limit); it was converted in greyscale ("
                + letter.size() + " bytes)."));
      }
      for (Path intermediate : intermediates.byPart().values()) {
        Files.deleteIfExists(intermediate);
      }
      if (options.maxOutputBytes() > 0 && letter.size() > options.maxOutputBytes()) {
        throw new ConversionException(Reason.OUTPUT_TOO_LARGE,
            title + " is " + letter.size() + " bytes as a print-ready PDF, above the "
                + options.maxOutputBytes() + " byte limit even in " + (letter.options().colour()
                == PrintColour.GRAYSCALE ? "greyscale" : "CMYK") + ".");
      }
      ConvertedPdf result =
          new ConvertedPdf(letter.file(), work, letter.pages(), letter.mediaType(), warnings);
      succeeded = true;
      return result;
    } catch (IOException e) {
      throw localIoFailure(title, e);
    } catch (RuntimeException e) {
      throw new ConversionException(Reason.INVALID_CONTENT,
          title + " could not be converted: " + e.getClass().getSimpleName(), e);
    } finally {
      // Also on an Error, such as running out of memory on a huge page.
      if (!succeeded) {
        WorkDirectory.deleteRecursively(work);
      }
    }
  }

  private record Assembled(Path file, int pages, long size, String mediaType,
      List<ConversionWarning> warnings, PrintOptions options) {
  }

  /**
   * The PDFs converters produced for a letter's parts, kept in {@code dir} for the life of the
   * letter so a greyscale retry does not convert again (or call Docmosis again).
   */
  private record Intermediates(Path dir, Map<PrintPart, Path> byPart) {
  }

  private Assembled assemble(String title, List<PrintPart> parts, PrintOptions options,
      Intermediates intermediates, Path dir) throws ConversionException, IOException {
    List<ConversionWarning> warnings = new ArrayList<>();
    List<Path> pieces = new ArrayList<>();
    String mediaType = SupportedMediaTypes.PDF;
    int pages = 0;
    for (PrintPart part : parts) {
      switch (part) {
        case PrintPart.Convert convert -> {
          Path piece = WorkDirectory.createFile(dir, ".pdf");
          // A lone converted part may be the finished letter, so it carries the letter's title.
          String pieceTitle = parts.size() == 1 ? title : convert.source().fileName();
          Converted converted = convertInto(convert.source(), options, pieceTitle, dir, piece,
              warnings, intermediates, convert);
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
      boolean allPrintReady = parts.stream().allMatch(PrintPart.Convert.class::isInstance);
      pages = PrintAssembler.join(pieces, title, options, allPrintReady, dir, output);
      if (!allPrintReady && options.standard() == PdfStandard.PDF_X_1A_2001) {
        warnings.add(new ConversionWarning(Code.STANDARD_NOT_CLAIMED, title + " includes pages"
            + " added as they are, which may not meet " + PdfX1a.CONFORMANCE + ", so it is not"
            + " identified as " + PdfX1a.CONFORMANCE + ". Its converted pages meet it."));
      }
    }
    return new Assembled(output, pages, Files.size(output), mediaType, warnings, options);
  }

  private ConvertedPdf convert(ConversionSource source, PrintOptions print)
      throws ConversionException {
    Path work = createWorkDirectory();
    boolean succeeded = false;
    try {
      List<ConversionWarning> warnings = new ArrayList<>();
      Path output = WorkDirectory.createFile(work, ".pdf");
      Converted converted =
          convertInto(source, print, source.fileName(), work, output, warnings, null, null);
      ConvertedPdf result =
          new ConvertedPdf(output, work, converted.pages(), converted.mediaType(), warnings);
      succeeded = true;
      return result;
    } catch (IOException e) {
      throw localIoFailure(source.fileName(), e);
    } catch (RuntimeException e) {
      throw new ConversionException(Reason.INVALID_CONTENT,
          source.fileName() + " could not be converted: " + e.getClass().getSimpleName(), e);
    } finally {
      if (!succeeded) {
        WorkDirectory.deleteRecursively(work);
      }
    }
  }

  /** Local disk trouble (full, unwritable) says nothing about the document: worth a retry. */
  private static ConversionException localIoFailure(String name, IOException e) {
    return new ConversionException(Reason.IO_FAILURE,
        "Local I/O failed while converting " + name, true, e);
  }

  private record Converted(int pages, String mediaType) {
  }

  /**
   * Converts one source into {@code output}; {@code print} is null for plain conversion, and
   * {@code title} is written to print output. With {@code intermediates}, a converter runs once
   * per {@code part} and its PDF is kept there.
   */
  private Converted convertInto(ConversionSource source, PrintOptions print, String title,
      Path work, Path output, List<ConversionWarning> warnings, Intermediates intermediates,
      PrintPart part) throws ConversionException, IOException {
    String type = effectiveType(source, warnings);
    PdfConverter converter = converterFor(source, type);
    ConversionSource routed = new ConversionSource(source.file(), source.fileName(), type);
    if (print == null) {
      runConverter(converter, routed, output, work);
      return new Converted(requireUsableOutput(converter, routed, output, work), type);
    }
    PrintRenderer renderer = new PrintRenderer(print, work);
    if (converter instanceof BuiltInConverters.PrintsDirectly direct) {
      return new Converted(direct.renderForPrint(routed, renderer, title, work, output), type);
    }
    Path pdf = intermediates == null ? null : intermediates.byPart().get(part);
    boolean keep = intermediates != null;
    if (pdf == null) {
      pdf = WorkDirectory.createFile(keep ? intermediates.dir() : work, ".converted.pdf");
      runConverter(converter, routed, pdf, keep ? intermediates.dir() : work);
      if (keep) {
        intermediates.byPart().put(part, pdf);
      }
    }
    try (PDDocument document = openUsableOutput(converter, routed, pdf, work)) {
      return new Converted(renderer.renderPdf(document, title, output), type);
    } finally {
      if (!keep) {
        Files.deleteIfExists(pdf);
      }
    }
  }

  private PdfConverter converterFor(ConversionSource source, String type)
      throws ConversionException {
    PdfConverter converter = converters.get(type);
    if (converter != null) {
      return converter;
    }
    if (SupportedMediaTypes.OFFICE.contains(type) && !docmosisConverts) {
      throw new ConversionException(Reason.CONVERTER_NOT_CONFIGURED,
          source.fileName() + " is '" + type + "', which needs Docmosis, and no Docmosis "
              + "client is configured. Call docmosis(...) on the converter builder, or set "
              + "ccd.conversion.docmosis.convert-endpoint and access-key.", type);
    }
    throw new ConversionException(Reason.UNSUPPORTED_MEDIA_TYPE,
        source.fileName() + " is '" + (type.isEmpty() ? "of no known type" : type)
            + "', which cannot be converted. Supported types: " + supportedMediaTypes(), type);
  }

  private static void runConverter(PdfConverter converter, ConversionSource source, Path output,
      Path scratch) throws ConversionException, IOException {
    AtomicInteger allocations = new AtomicInteger();
    ConversionContext context = suffix -> {
      if (allocations.incrementAndGet() > MAX_TEMP_FILES_PER_CONVERSION) {
        throw new IOException("The converter for " + source.mediaType() + " allocated more than "
            + MAX_TEMP_FILES_PER_CONVERSION + " temporary files for " + source.fileName());
      }
      return WorkDirectory.createFile(scratch, suffix);
    };
    converter.convert(source, output, context);
  }

  /** Checks a converter's output is a usable PDF, returning its page count. */
  private static int requireUsableOutput(PdfConverter converter, ConversionSource source,
      Path pdf, Path work) throws ConversionException, IOException {
    try (PDDocument document = openUsableOutput(converter, source, pdf, work)) {
      return document.getNumberOfPages();
    }
  }

  private static PDDocument openUsableOutput(PdfConverter converter, ConversionSource source,
      Path pdf, Path work) throws ConversionException {
    try {
      return PdfChecks.openUsablePdf(pdf, work);
    } catch (ConversionException e) {
      // A PDF source's problems are the source's; any other converter produced a bad PDF.
      String what = converter instanceof BuiltInConverters.Pdf ? ""
          : "the PDF it was converted to is unusable: ";
      throw new ConversionException(e.reason(), source.fileName() + ": " + what + e.getMessage(),
          e);
    }
  }

  private Path createWorkDirectory() throws ConversionException {
    try {
      return WorkDirectory.create(tempDirectory);
    } catch (IOException e) {
      throw new ConversionException(Reason.IO_FAILURE,
          "Could not create a working directory under " + tempDirectory, true, e);
    }
  }

  interface Check<T> {
    T run() throws ConversionException;
  }

  /** Runs a check, naming the source in any failure. */
  static <T> T named(ConversionSource source, Check<T> check) throws ConversionException {
    try {
      return check.run();
    } catch (ConversionException e) {
      throw new ConversionException(e.reason(), source.fileName() + ": " + e.getMessage(), e);
    }
  }

  private static String effectiveType(ConversionSource source, List<ConversionWarning> warnings)
      throws ConversionException {
    String declared = MediaTypeDetection.normalise(source.mediaType());
    Optional<String> inferred = UNTYPED.contains(declared)
        ? MediaTypeDetection.fromFileName(source.fileName()) : Optional.empty();
    byte[] head = new byte[MediaTypeDetection.DETECTION_PREFIX_BYTES];
    int length;
    try (InputStream in = Files.newInputStream(source.file())) {
      length = in.readNBytes(head, 0, head.length);
    } catch (IOException e) {
      throw new ConversionException(Reason.IO_FAILURE,
          "Could not read " + source.fileName(), true, e);
    }
    // What the caller told us, for messages: an inferred type was never declared.
    String claim = inferred.isPresent()
        ? "has no usable media type and its file name suggests '" + inferred.get() + "'"
        : "was declared '" + declared + "'";
    return switch (MediaTypeDetection.route(inferred.orElse(declared), head, length)) {
      case MediaTypeDetection.Routing.Route route -> {
        inferred.ifPresent(type -> warnings.add(new ConversionWarning(Code.MEDIA_TYPE_INFERRED,
            source.fileName() + " has no usable media type; '" + type
                + "' was inferred from its file name.")));
        yield route.mediaType();
      }
      case MediaTypeDetection.Routing.RouteWithMismatch mismatch -> {
        warnings.add(UNTYPED.contains(declared)
            ? new ConversionWarning(Code.MEDIA_TYPE_INFERRED,
                source.fileName() + " has no usable media type"
                    + inferred.map(type -> " and its file name suggests '" + type + "'").orElse("")
                    + "; its content is '" + mismatch.detected() + "'.")
            : new ConversionWarning(Code.MEDIA_TYPE_MISMATCH,
                source.fileName() + " was declared '" + declared
                    + "' but its content is '" + mismatch.detected()
                    + "'; it was converted as '" + mismatch.mediaType() + "'."));
        yield mismatch.mediaType();
      }
      case MediaTypeDetection.Routing.Irreconcilable conflict ->
          throw new ConversionException(Reason.MEDIA_TYPE_CONFLICT,
              source.fileName() + " " + claim + " but is " + conflict.detectedDescription() + ".");
    };
  }

  /**
   * Builds a {@link DocumentConverter}.
   */
  public static final class Builder {
    private final Map<String, PdfConverter> registered = new LinkedHashMap<>();
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
     * Registers a converter for its {@link PdfConverter#mediaTypes()}. It takes precedence over a
     * built-in converter for the same type, so this is also how a built-in is replaced. Two
     * registered converters cannot claim the same type.
     *
     * @throws IllegalArgumentException if the converter claims no types, or a type another
     *     registered converter already claims
     */
    public Builder converter(PdfConverter converter) {
      Objects.requireNonNull(converter, "converter must be provided");
      Set<String> types = Objects.requireNonNull(converter.mediaTypes(),
          converter.getClass().getName() + ".mediaTypes() must not be null");
      if (types.isEmpty()) {
        throw new IllegalArgumentException(
            converter.getClass().getName() + " handles no media types");
      }
      Map<String, PdfConverter> claimed = new LinkedHashMap<>();
      for (String declared : types) {
        String type = MediaTypeDetection.normalise(declared);
        if (type.isEmpty()) {
          throw new IllegalArgumentException(
              converter.getClass().getName() + " declares a blank media type");
        }
        PdfConverter existing = registered.get(type);
        if (existing != null && existing != converter) {
          throw new IllegalArgumentException("Both " + existing.getClass().getName() + " and "
              + converter.getClass().getName() + " convert '" + type + "'");
        }
        claimed.put(type, converter);
      }
      registered.putAll(claimed);
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
      Map<String, PdfConverter> effective = new LinkedHashMap<>();
      register(effective, new BuiltInConverters.Pdf());
      register(effective, new BuiltInConverters.Image());
      boolean docmosisConverts = docmosis != null && docmosis.convertsFiles();
      if (docmosisConverts) {
        register(effective, new BuiltInConverters.DocmosisOffice(docmosis));
      }
      registered.forEach((type, converter) -> {
        PdfConverter builtIn = effective.put(type, converter);
        if (builtIn != null) {
          log.info("{} replaces the built-in conversion of '{}'",
              converter.getClass().getName(), type);
        }
      });
      return new DocumentConverter(effective, docmosisConverts,
          tempDirectory != null ? tempDirectory : Path.of(System.getProperty("java.io.tmpdir")));
    }

    private static void register(Map<String, PdfConverter> effective, PdfConverter converter) {
      converter.mediaTypes().forEach(type -> effective.put(type, converter));
    }
  }
}
