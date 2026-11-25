package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * A bundle: documents in titled sections, with a contents page, bookmarks and page numbers.
 *
 * <pre>{@code
 * BundleRequest request = BundleRequest.builder("Hearing bundle")
 *     .section(BundleSection.builder("Applications")
 *         .document(caseData.getApplication())       // a CCD Document, fetched from CDAM
 *         .build())
 *     .section(BundleSection.builder("Evidence")
 *         .document(HtmlPage.builder().title("Hearing recording").body(html).build())
 *         .build())
 *     .build();
 * }</pre>
 *
 * <p>By default the bundle has a contents page, a cover sheet per section and
 * {@code N of M} page numbers at the foot of each page, and a document that cannot be included
 * is replaced by a page saying why. {@link Builder#print} lays the finished bundle out for bulk
 * print instead, which loses its links, bookmarks and selectable text.
 */
public final class BundleRequest implements DocRequest {
  private final BundleSection root;
  private final String fileName;
  private final boolean tableOfContents;
  private final boolean sectionCoverSheets;
  private final boolean documentCoverSheets;
  private final PageNumbers pageNumbers;
  private final boolean confidentialMarking;
  private final DocSource coverPage;
  private final Watermark watermark;
  private final PrintOptions print;
  private final MissingDocuments missingDocuments;

  private BundleRequest(Builder builder) {
    this.root = builder.root.build();
    this.fileName = builder.fileName != null ? builder.fileName : DocRequest.fileNameFor(builder.title);
    this.tableOfContents = builder.tableOfContents;
    this.sectionCoverSheets = builder.sectionCoverSheets;
    this.documentCoverSheets = builder.documentCoverSheets;
    this.pageNumbers = builder.pageNumbers;
    this.confidentialMarking = builder.confidentialMarking;
    this.coverPage = builder.coverPage;
    this.watermark = builder.watermark;
    this.print = builder.print;
    this.missingDocuments = builder.missingDocuments;
  }

  /** Starts a bundle with this title. */
  public static Builder builder(String title) {
    return new Builder(title);
  }

  @Override
  public String title() {
    return root.title();
  }

  @Override
  public String fileName() {
    return fileName;
  }

  /** The bundle's top level: its documents and sections, under the bundle's title. */
  public BundleSection root() {
    return root;
  }

  /** Every document, depth first, in bundle order. */
  public List<BundleDocument> allDocuments() {
    List<BundleDocument> all = new ArrayList<>();
    collect(root, all);
    return all;
  }

  private static void collect(BundleSection section, List<BundleDocument> into) {
    into.addAll(section.documents());
    section.sections().forEach(child -> collect(child, into));
  }

  /** Whether the bundle starts with a contents page. */
  public boolean tableOfContents() {
    return tableOfContents;
  }

  /** Whether each section starts with a cover sheet. */
  public boolean sectionCoverSheets() {
    return sectionCoverSheets;
  }

  /** Whether each document starts with a cover sheet. */
  public boolean documentCoverSheets() {
    return documentCoverSheets;
  }

  /** Where page numbers go. */
  public PageNumbers pageNumbers() {
    return pageNumbers;
  }

  /** Whether confidential documents' pages are marked as such. */
  public boolean confidentialMarking() {
    return confidentialMarking;
  }

  /** A document placed before the contents page. */
  public Optional<DocSource> coverPage() {
    return Optional.ofNullable(coverPage);
  }

  @Override
  public Optional<Watermark> watermark() {
    return Optional.ofNullable(watermark);
  }

  @Override
  public Optional<PrintOptions> print() {
    return Optional.ofNullable(print);
  }

  /** What happens to a document that cannot be included. */
  public MissingDocuments missingDocuments() {
    return missingDocuments;
  }

  /**
   * Builds a {@link BundleRequest}.
   */
  public static final class Builder {
    private final String title;
    private final BundleSection.Builder root;
    private String fileName;
    private boolean tableOfContents = true;
    private boolean sectionCoverSheets = true;
    private boolean documentCoverSheets;
    private PageNumbers pageNumbers = PageNumbers.BOTTOM_CENTRE_N_OF_M;
    private boolean confidentialMarking = true;
    private DocSource coverPage;
    private Watermark watermark;
    private PrintOptions print;
    private MissingDocuments missingDocuments = MissingDocuments.PLACEHOLDER;

    private Builder(String title) {
      this.title = Validate.requireNonBlank(title, "BundleRequest.title");
      this.root = BundleSection.builder(title);
    }

    /**
     * The result's file name; defaults to the title with {@code .pdf}.
     */
    public Builder fileName(String fileName) {
      this.fileName = Validate.requireNonBlank(fileName, "BundleRequest.fileName");
      return this;
    }

    /** Adds a section. */
    public Builder section(BundleSection section) {
      root.section(section);
      return this;
    }

    /** Adds sections. */
    public Builder sections(List<BundleSection> sections) {
      Validate.requireNonNull(sections, "sections").forEach(root::section);
      return this;
    }

    /** Adds a document at the top level, before any section. */
    public Builder document(BundleDocument document) {
      root.document(document);
      return this;
    }

    /** Adds a CDAM document at the top level, listed under its file name. */
    public Builder document(Document document) {
      root.document(document);
      return this;
    }

    /** Adds a document at the top level, listed under its source's name. */
    public Builder document(DocSource source) {
      root.document(source);
      return this;
    }

    /** Whether the bundle starts with a contents page. Default true. */
    public Builder tableOfContents(boolean tableOfContents) {
      this.tableOfContents = tableOfContents;
      return this;
    }

    /** Whether each section starts with a cover sheet. Default true. */
    public Builder sectionCoverSheets(boolean sectionCoverSheets) {
      this.sectionCoverSheets = sectionCoverSheets;
      return this;
    }

    /** Whether each document starts with a cover sheet. Default false. */
    public Builder documentCoverSheets(boolean documentCoverSheets) {
      this.documentCoverSheets = documentCoverSheets;
      return this;
    }

    /**
     * Where page numbers go. Default {@link PageNumbers#BOTTOM_CENTRE_N_OF_M}.
     */
    public Builder pageNumbers(PageNumbers pageNumbers) {
      this.pageNumbers = Validate.requireNonNull(pageNumbers, "pageNumbers");
      return this;
    }

    /** Whether confidential documents' pages are marked as such. Default true. */
    public Builder confidentialMarking(boolean confidentialMarking) {
      this.confidentialMarking = confidentialMarking;
      return this;
    }

    /** A document placed before the contents page, such as a court's front sheet. */
    public Builder coverPage(DocSource coverPage) {
      this.coverPage = Validate.requireNonNull(coverPage, "coverPage");
      return this;
    }

    /** A watermark on each document's pages. */
    public Builder watermark(Watermark watermark) {
      this.watermark = Validate.requireNonNull(watermark, "watermark");
      return this;
    }

    /** Lays the finished bundle out for bulk print. */
    public Builder print(PrintOptions print) {
      this.print = Validate.requireNonNull(print, "print");
      return this;
    }

    /** What happens to a document that cannot be included. Default placeholder pages. */
    public Builder missingDocuments(MissingDocuments missingDocuments) {
      this.missingDocuments = Validate.requireNonNull(missingDocuments, "missingDocuments");
      return this;
    }

    /** Builds the request. */
    public BundleRequest build() {
      return new BundleRequest(this);
    }
  }
}
