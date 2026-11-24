package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.time.LocalDate;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * A document in a bundle: its source and how it is listed in the contents.
 *
 * <pre>{@code
 * BundleDocument.builder(DocSource.of(claim.getForm()))
 *     .title("Claim form")
 *     .date(claim.getIssueDate())
 *     .build()
 * }</pre>
 */
public final class BundleDocument {
  private final DocSource source;
  private final String title;
  private final LocalDate date;
  private final boolean confidential;

  private BundleDocument(Builder builder) {
    this.source = builder.source;
    this.title = builder.title == null || builder.title.isBlank()
        ? builder.source.name() : builder.title;
    this.date = builder.date;
    this.confidential = builder.confidential;
  }

  /** A CDAM document listed under its file name. */
  public static BundleDocument of(Document document) {
    return builder(DocSource.of(document)).build();
  }

  /** A document listed under its source's name. */
  public static BundleDocument of(DocSource source) {
    return builder(source).build();
  }

  /** Starts a document. */
  public static Builder builder(DocSource source) {
    return new Builder(source);
  }

  /** Where the content comes from. */
  public DocSource source() {
    return source;
  }

  /** The title in the contents and bookmarks. */
  public String title() {
    return title;
  }

  /** The date shown in the contents. */
  public Optional<LocalDate> date() {
    return Optional.ofNullable(date);
  }

  /** Whether the document's pages carry the confidential marking. */
  public boolean confidential() {
    return confidential;
  }

  /**
   * Builds a {@link BundleDocument}.
   */
  public static final class Builder {
    private final DocSource source;
    private String title;
    private LocalDate date;
    private boolean confidential;

    private Builder(DocSource source) {
      this.source = Validate.requireNonNull(source, "BundleDocument.source");
    }

    /** The title in the contents; defaults to the source's name. */
    public Builder title(String title) {
      this.title = title;
      return this;
    }

    /** The date shown in the contents. */
    public Builder date(LocalDate date) {
      this.date = date;
      return this;
    }

    /** Marks the document's pages confidential, when the bundle's marking is on. */
    public Builder confidential(boolean confidential) {
      this.confidential = confidential;
      return this;
    }

    /** Builds the document. */
    public BundleDocument build() {
      return new BundleDocument(this);
    }
  }
}
