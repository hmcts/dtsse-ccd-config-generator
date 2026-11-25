package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.ArrayList;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.type.Document;

/** A titled section of a {@link PdfRequest}: its parts, then its sub-sections, in order. */
public final class PdfSection {
  private final String title;
  private final List<PdfPart> parts;
  private final List<PdfSection> sections;
  private final boolean placeholderWhenEmpty;

  private PdfSection(Builder builder) {
    this.title = builder.title;
    this.parts = List.copyOf(builder.parts);
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

  /** The section's own parts. */
  public List<PdfPart> parts() {
    return parts;
  }

  /** The section's sub-sections. */
  public List<PdfSection> sections() {
    return sections;
  }

  /** Whether an empty section gets a page saying so, rather than being left out. */
  public boolean placeholderWhenEmpty() {
    return placeholderWhenEmpty;
  }

  /** Whether the section, or one inside it, has a part or would show an empty-section page. */
  boolean hasContent() {
    return !parts.isEmpty() || placeholderWhenEmpty
        || sections.stream().anyMatch(PdfSection::hasContent);
  }

  void collectParts(List<PdfPart> into) {
    into.addAll(parts);
    sections.forEach(section -> section.collectParts(into));
  }

  /** Builds a {@link PdfSection}. */
  public static final class Builder {
    private final String title;
    private final List<PdfPart> parts = new ArrayList<>();
    private final List<PdfSection> sections = new ArrayList<>();
    private boolean placeholderWhenEmpty;

    private Builder(String title) {
      this.title = Validate.requireNonBlank(title, "PdfSection.title");
    }

    /** Adds a part. */
    public Builder document(PdfPart part) {
      parts.add(Validate.requireNonNull(part, "part"));
      return this;
    }

    /** Adds a CDAM document, listed under its file name. */
    public Builder document(Document document) {
      return document(PdfPart.of(document));
    }

    /** Adds a document listed under its source's name. */
    public Builder document(DocSource source) {
      return document(PdfPart.of(source));
    }

    /** Adds parts. */
    public Builder documents(List<PdfPart> parts) {
      Validate.requireNonNull(parts, "parts").forEach(this::document);
      return this;
    }

    /** Adds a sub-section. */
    public Builder section(PdfSection section) {
      sections.add(Validate.requireNonNull(section, "section"));
      return this;
    }

    /** Includes a page saying the section is empty, rather than leaving it out. */
    public Builder placeholderWhenEmpty() {
      this.placeholderWhenEmpty = true;
      return this;
    }

    /** Builds the section. */
    public PdfSection build() {
      return new PdfSection(this);
    }
  }
}
