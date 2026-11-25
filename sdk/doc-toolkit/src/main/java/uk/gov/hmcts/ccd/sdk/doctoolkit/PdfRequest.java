package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * What to render. With documents only it is a plain join, in order; bundle features (a title
 * page, contents, cover sheets, page numbers, sections) are switched on one by one.
 *
 * <pre>{@code
 * // A letter for bulk print
 * PdfRequest.builder("CPC-01 1234")
 *     .document(PdfPart.builder(DocSource.bytes(coversheet, "coversheet.pdf")).unchanged().build())
 *     .document(claimForm)
 *     .print(PrintOptions.bulkPrint())
 *     .build();
 *
 * // A bundle
 * PdfRequest.builder("Case bundle")
 *     .section(PdfSection.builder("Claim").documents(parts).placeholderWhenEmpty().build())
 *     .titlePage()
 *     .tableOfContents()
 *     .sectionCoverSheets()
 *     .pageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M)
 *     .missingDocuments(MissingDocuments.PLACEHOLDER)
 *     .build();
 * }</pre>
 */
public final class PdfRequest {
  private final PdfSection root;
  private final String fileName;
  private final DocSource coverPage;
  private final boolean titlePage;
  private final boolean tableOfContents;
  private final boolean sectionCoverSheets;
  private final boolean documentCoverSheets;
  private final PageNumbers pageNumbers;
  private final boolean confidentialMarking;
  private final Watermark watermark;
  private final MissingDocuments missingDocuments;
  private final PrintOptions print;
  private final String category;
  private final Classification classification;

  private PdfRequest(Builder builder) {
    this.root = builder.root.build();
    this.fileName = builder.fileName != null ? builder.fileName : fileNameFor(builder.title);
    this.coverPage = builder.coverPage;
    this.titlePage = builder.titlePage;
    this.tableOfContents = builder.tableOfContents;
    this.sectionCoverSheets = builder.sectionCoverSheets;
    this.documentCoverSheets = builder.documentCoverSheets;
    this.pageNumbers = builder.pageNumbers;
    this.confidentialMarking = builder.confidentialMarking;
    this.watermark = builder.watermark;
    this.missingDocuments = builder.missingDocuments;
    this.print = builder.print;
    this.category = builder.category;
    this.classification = builder.classification;
  }

  /** Starts a request; the title is written to the PDF and heads its contents. */
  public static Builder builder(String title) {
    return new Builder(title);
  }

  /** The title written to the PDF. */
  public String title() {
    return root.title();
  }

  /** The file name of the result. */
  public String fileName() {
    return fileName;
  }

  /** The top level: parts before sections. Its title is the request's. */
  public PdfSection root() {
    return root;
  }

  /** Every part, depth first, in output order. */
  public List<PdfPart> allParts() {
    List<PdfPart> all = new ArrayList<>();
    root.collectParts(all);
    return all;
  }

  /** A page placed before everything else, including the title page. */
  public Optional<DocSource> coverPage() {
    return Optional.ofNullable(coverPage);
  }

  /** Whether the output starts with a page giving the title. */
  public boolean titlePage() {
    return titlePage;
  }

  /** Whether the output has a contents page linking to each part. */
  public boolean tableOfContents() {
    return tableOfContents;
  }

  /** Whether each section starts with a page giving its title. */
  public boolean sectionCoverSheets() {
    return sectionCoverSheets;
  }

  /** Whether each part starts with a page giving its title. */
  public boolean documentCoverSheets() {
    return documentCoverSheets;
  }

  /** Where page numbers are stamped. */
  public PageNumbers pageNumbers() {
    return pageNumbers;
  }

  /** Whether confidential parts' pages carry the confidential marking. */
  public boolean confidentialMarking() {
    return confidentialMarking;
  }

  /** The watermark on each part's pages; generated pages are not marked. */
  public Optional<Watermark> watermark() {
    return Optional.ofNullable(watermark);
  }

  /** What happens to a part that cannot be included. */
  public MissingDocuments missingDocuments() {
    return missingDocuments;
  }

  /** The print layout, when the result is for bulk print. */
  public Optional<PrintOptions> print() {
    return Optional.ofNullable(print);
  }

  /** The case file category put on the uploaded document. */
  public Optional<String> category() {
    return Optional.ofNullable(category);
  }

  /** The classification the uploaded document is stored with. */
  public Classification classification() {
    return classification;
  }

  /**
   * Whether the output is assembled as a bundle (anything beyond a plain join). A printed bundle
   * is assembled first and printed as a whole; a printed plain join prints each part on its own,
   * so each starts on a new sheet and {@link PdfPart#unchanged()} parts keep their layout.
   */
  public boolean assembled() {
    return !root.sections().isEmpty() || coverPage != null || titlePage || tableOfContents
        || sectionCoverSheets || documentCoverSheets || pageNumbers != PageNumbers.NONE
        || root.placeholderWhenEmpty();
  }

  /**
   * A file name for {@code title}: unsafe characters replaced, {@code .pdf} added.
   */
  static String fileNameFor(String title) {
    String base = title.trim().replaceAll("[^A-Za-z0-9._ -]", "").replaceAll("\\s+", "-");
    return (base.isEmpty() ? "document" : base) + ".pdf";
  }

  /**
   * Builds a {@link PdfRequest}.
   */
  public static final class Builder {
    private final String title;
    private final PdfSection.Builder root;
    private String fileName;
    private DocSource coverPage;
    private boolean titlePage;
    private boolean tableOfContents;
    private boolean sectionCoverSheets;
    private boolean documentCoverSheets;
    private PageNumbers pageNumbers = PageNumbers.NONE;
    private boolean confidentialMarking;
    private Watermark watermark;
    private MissingDocuments missingDocuments = MissingDocuments.FAIL;
    private PrintOptions print;
    private String category;
    private Classification classification = Classification.PUBLIC;

    private Builder(String title) {
      this.title = Validate.requireNonBlank(title, "PdfRequest.title");
      this.root = PdfSection.builder(title);
    }

    /**
     * The result's file name; defaults to the title plus {@code .pdf}.
     */
    public Builder fileName(String fileName) {
      this.fileName = Validate.requireNonBlank(fileName, "PdfRequest.fileName");
      return this;
    }

    /** Adds a top-level part. Top-level parts come before sections. */
    public Builder document(PdfPart part) {
      root.document(part);
      return this;
    }

    /** Adds a CDAM document, listed under its file name. */
    public Builder document(Document document) {
      root.document(document);
      return this;
    }

    /** Adds a document listed under its source's name. */
    public Builder document(DocSource source) {
      root.document(source);
      return this;
    }

    /** Adds top-level parts. */
    public Builder documents(List<PdfPart> parts) {
      root.documents(parts);
      return this;
    }

    /** Adds a section. */
    public Builder section(PdfSection section) {
      root.section(section);
      return this;
    }

    /** Adds sections. */
    public Builder sections(List<PdfSection> sections) {
      Validate.requireNonNull(sections, "sections").forEach(root::section);
      return this;
    }

    /** A page before everything else. */
    public Builder coverPage(DocSource coverPage) {
      this.coverPage = Validate.requireNonNull(coverPage, "coverPage");
      return this;
    }

    /** Starts the output with a page giving the title. */
    public Builder titlePage() {
      this.titlePage = true;
      return this;
    }

    /** Adds a contents page linking to each part. */
    public Builder tableOfContents() {
      this.tableOfContents = true;
      return this;
    }

    /** Starts each section with a page giving its title. */
    public Builder sectionCoverSheets() {
      this.sectionCoverSheets = true;
      return this;
    }

    /** Starts each part with a page giving its title. */
    public Builder documentCoverSheets() {
      this.documentCoverSheets = true;
      return this;
    }

    /** Stamps page numbers. */
    public Builder pageNumbers(PageNumbers pageNumbers) {
      this.pageNumbers = Validate.requireNonNull(pageNumbers, "pageNumbers");
      return this;
    }

    /** Marks confidential parts' pages. */
    public Builder confidentialMarking() {
      this.confidentialMarking = true;
      return this;
    }

    /** Puts an image on each part's pages. */
    public Builder watermark(Watermark watermark) {
      this.watermark = Validate.requireNonNull(watermark, "watermark");
      return this;
    }

    /** What happens to a part that cannot be included; defaults to failing. */
    public Builder missingDocuments(MissingDocuments missingDocuments) {
      this.missingDocuments = Validate.requireNonNull(missingDocuments, "missingDocuments");
      return this;
    }

    /** Lays the output out for bulk print. */
    public Builder print(PrintOptions print) {
      this.print = Validate.requireNonNull(print, "print");
      return this;
    }

    /** The case file category put on the uploaded document. */
    public Builder category(String category) {
      this.category = Validate.requireNonBlank(category, "category");
      return this;
    }

    /** The classification the uploaded document is stored with; defaults to PUBLIC. */
    public Builder classification(Classification classification) {
      this.classification = Validate.requireNonNull(classification, "classification");
      return this;
    }

    /**
     * Builds the request.
     *
     * @throws IllegalArgumentException if it would produce nothing, or combines options that
     *     cannot apply together
     */
    public PdfRequest build() {
      PdfRequest request = new PdfRequest(this);
      if (!request.root.hasContent()) {
        throw new IllegalArgumentException(
            "PdfRequest '" + title + "' has no documents and no empty-section pages");
      }
      boolean anyUnchanged = request.allParts().stream().anyMatch(PdfPart::unchanged);
      if (anyUnchanged && (request.print == null || request.assembled())) {
        throw new IllegalArgumentException("Unchanged parts apply only to a printed plain join:"
            + " a bundle is printed as a whole, and without printing nothing is changed");
      }
      if (request.print != null && !request.assembled()
          && request.missingDocuments == MissingDocuments.PLACEHOLDER) {
        throw new IllegalArgumentException("A printed plain join cannot have placeholder pages:"
            + " a letter is sent complete or not at all");
      }
      return request;
    }
  }
}
