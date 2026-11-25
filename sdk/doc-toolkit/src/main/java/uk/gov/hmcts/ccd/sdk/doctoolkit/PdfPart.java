package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.time.LocalDate;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * One document in a {@link PdfRequest}: where its content comes from and how it is listed.
 *
 * <pre>{@code
 * PdfPart.builder(DocSource.of(claim.getForm()))
 *     .id(claim.getId().toString())
 *     .title("Claim form")
 *     .date(claim.getIssueDate())
 *     .build()
 * }</pre>
 */
public final class PdfPart {
  private final DocSource source;
  private final String id;
  private final String title;
  private final LocalDate date;
  private final boolean confidential;
  private final boolean unchanged;

  private PdfPart(Builder builder) {
    this.source = builder.source;
    this.id = builder.id;
    this.title = builder.title == null || builder.title.isBlank()
        ? builder.source.name() : builder.title;
    this.date = builder.date;
    this.confidential = builder.confidential;
    this.unchanged = builder.unchanged;
  }

  /** A CDAM document listed under its file name. */
  public static PdfPart of(Document document) {
    return builder(DocSource.of(document)).build();
  }

  /** A document listed under its source's name. */
  public static PdfPart of(DocSource source) {
    return builder(source).build();
  }

  /** Starts a part. */
  public static Builder builder(DocSource source) {
    return new Builder(source);
  }

  /** Where the content comes from. */
  public DocSource source() {
    return source;
  }

  /** The caller's id for the part, echoed in {@link DocFailure#id()}. */
  public Optional<String> id() {
    return Optional.ofNullable(id);
  }

  /** The title in the contents and bookmarks. */
  public String title() {
    return title;
  }

  /** The date shown in the contents. */
  public Optional<LocalDate> date() {
    return Optional.ofNullable(date);
  }

  /** Whether the part's pages carry the confidential marking, when the request's is on. */
  public boolean confidential() {
    return confidential;
  }

  /**
   * Whether the part's pages are passed through exactly as they are, even when printing; for a
   * coversheet whose address must stay where the envelope window expects it.
   */
  public boolean unchanged() {
    return unchanged;
  }

  /** Builds a {@link PdfPart}. */
  public static final class Builder {
    private final DocSource source;
    private String id;
    private String title;
    private LocalDate date;
    private boolean confidential;
    private boolean unchanged;

    private Builder(DocSource source) {
      this.source = Validate.requireNonNull(source, "PdfPart.source");
    }

    /** The caller's id for the part, echoed in failures. */
    public Builder id(String id) {
      this.id = id;
      return this;
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

    /** Marks the part's pages confidential, when the request's marking is on. */
    public Builder confidential() {
      this.confidential = true;
      return this;
    }

    /** Passes the part's pages through exactly as they are. */
    public Builder unchanged() {
      this.unchanged = true;
      return this;
    }

    /** Builds the part. */
    public PdfPart build() {
      return new PdfPart(this);
    }
  }
}
