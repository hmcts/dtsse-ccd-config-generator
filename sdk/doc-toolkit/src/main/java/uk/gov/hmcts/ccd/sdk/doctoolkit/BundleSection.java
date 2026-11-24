package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.ArrayList;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.type.Document;

/** A titled section of a bundle: documents, then sub-sections, in order. */
public final class BundleSection {
  private final String title;
  private final List<BundleDocument> documents;
  private final List<BundleSection> sections;
  private final boolean placeholderWhenEmpty;

  private BundleSection(Builder builder) {
    this.title = builder.title;
    this.documents = List.copyOf(builder.documents);
    this.sections = List.copyOf(builder.sections);
    this.placeholderWhenEmpty = builder.placeholderWhenEmpty;
  }

  /** Starts a section. */
  public static Builder builder(String title) {
    return new Builder(title);
  }

  /** The section's title. */
  public String title() {
    return title;
  }

  /** The section's own documents. */
  public List<BundleDocument> documents() {
    return documents;
  }

  /** The section's sub-sections. */
  public List<BundleSection> sections() {
    return sections;
  }

  /** Whether an empty section gets a page saying so, rather than being left out. */
  public boolean placeholderWhenEmpty() {
    return placeholderWhenEmpty;
  }

  /**
   * Builds a {@link BundleSection}.
   */
  public static final class Builder {
    private final String title;
    private final List<BundleDocument> documents = new ArrayList<>();
    private final List<BundleSection> sections = new ArrayList<>();
    private boolean placeholderWhenEmpty;

    private Builder(String title) {
      this.title = Validate.requireNonBlank(title, "BundleSection.title");
    }

    /** Adds a document. */
    public Builder document(BundleDocument document) {
      documents.add(Validate.requireNonNull(document, "document"));
      return this;
    }

    /** Adds a CDAM document, listed under its file name. */
    public Builder document(Document document) {
      return document(BundleDocument.of(document));
    }

    /** Adds a document listed under its source's name. */
    public Builder document(DocSource source) {
      return document(BundleDocument.of(source));
    }

    /** Adds documents. */
    public Builder documents(List<BundleDocument> documents) {
      Validate.requireNonNull(documents, "documents").forEach(this::document);
      return this;
    }

    /** Adds a sub-section. */
    public Builder section(BundleSection section) {
      sections.add(Validate.requireNonNull(section, "section"));
      return this;
    }

    /** Includes a page saying the section is empty, rather than leaving it out. */
    public Builder placeholderWhenEmpty() {
      this.placeholderWhenEmpty = true;
      return this;
    }

    /** Builds the section. */
    public BundleSection build() {
      return new BundleSection(this);
    }
  }
}
