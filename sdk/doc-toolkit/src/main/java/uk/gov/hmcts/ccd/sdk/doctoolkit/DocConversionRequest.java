package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One or more documents converted to a single PDF, in order.
 *
 * <pre>{@code
 * DocConversionRequest.document(DocSource.of(photo))
 *     .print(PrintOptions.bulkPrint().toBuilder().resolutionDpi(200).build())
 *     .build();
 *
 * // A bulk print letter: the coversheet as rendered, then each document print-ready.
 * DocConversionRequest.unchanged(DocSource.bytes(coversheet, "coversheet.pdf"))
 *     .document(DocSource.cdam(claimFormId))
 *     .document(DocSource.cdam(defenceFormId))
 *     .title("CPC-01 1234-5678-9012-3456")
 *     .print(PrintOptions.bulkPrint())
 *     .build();
 * }</pre>
 *
 * <p>Without {@link Builder#print} each document keeps its layout. With it, each document's pages
 * are rendered to the print standard and, when {@link PrintOptions#padToEvenPages()} is set,
 * padded so the next starts on a new sheet. A document that cannot be converted fails the whole
 * request.
 */
public final class DocConversionRequest implements DocRequest {
  private final List<Part> parts;
  private final String title;
  private final String fileName;
  private final Watermark watermark;
  private final PrintOptions print;

  private DocConversionRequest(Builder builder) {
    this.parts = List.copyOf(builder.parts);
    this.title = builder.title != null ? builder.title : parts.getFirst().source().name();
    this.fileName = builder.fileName != null ? builder.fileName : DocRequest.fileNameFor(title);
    this.watermark = builder.watermark;
    this.print = builder.print;
  }

  /** Starts a request with this document first. */
  public static Builder document(DocSource source) {
    return new Builder().document(source);
  }

  /** Starts a request with this PDF first, its pages kept as they are even when printed. */
  public static Builder unchanged(DocSource pdf) {
    return new Builder().unchanged(pdf);
  }

  /** A request that converts one document to PDF. */
  public static DocConversionRequest of(DocSource source) {
    return document(source).build();
  }

  /** The documents, in order. */
  public List<Part> parts() {
    return parts;
  }

  @Override
  public String title() {
    return title;
  }

  @Override
  public String fileName() {
    return fileName;
  }

  @Override
  public Optional<Watermark> watermark() {
    return Optional.ofNullable(watermark);
  }

  @Override
  public Optional<PrintOptions> print() {
    return Optional.ofNullable(print);
  }

  /**
   * One document of the request.
   *
   * @param unchanged whether the document is a PDF whose pages are kept as they are, even when
   *     the request is printed (a coversheet the service rendered for the print supplier)
   */
  public record Part(DocSource source, boolean unchanged) {
    /** Validates the part. */
    public Part {
      Validate.requireNonNull(source, "source");
    }
  }

  /**
   * Builds a {@link DocConversionRequest}.
   */
  public static final class Builder {
    private final List<Part> parts = new ArrayList<>();
    private String title;
    private String fileName;
    private Watermark watermark;
    private PrintOptions print;

    private Builder() {
    }

    /** Adds a document, converted. */
    public Builder document(DocSource source) {
      parts.add(new Part(source, false));
      return this;
    }

    /** Adds a PDF whose pages are kept as they are, even when printed. */
    public Builder unchanged(DocSource pdf) {
      parts.add(new Part(pdf, true));
      return this;
    }

    /** The title written to the PDF; defaults to the first document's name. */
    public Builder title(String title) {
      this.title = Validate.requireNonBlank(title, "DocConversionRequest.title");
      return this;
    }

    /**
     * The result's file name; defaults to the title with {@code .pdf}.
     */
    public Builder fileName(String fileName) {
      this.fileName = Validate.requireNonBlank(fileName, "DocConversionRequest.fileName");
      return this;
    }

    /** A watermark on each converted document's pages. */
    public Builder watermark(Watermark watermark) {
      this.watermark = Validate.requireNonNull(watermark, "watermark");
      return this;
    }

    /** Lays the result out for bulk print. */
    public Builder print(PrintOptions print) {
      this.print = Validate.requireNonNull(print, "print");
      return this;
    }

    /** Builds the request. */
    public DocConversionRequest build() {
      if (parts.isEmpty()) {
        throw new IllegalArgumentException("DocConversionRequest needs at least one document");
      }
      return new DocConversionRequest(this);
    }
  }
}
