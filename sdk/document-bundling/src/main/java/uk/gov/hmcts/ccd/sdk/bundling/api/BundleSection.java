package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * An ordered, optionally nested section of a bundle.
 *
 * <p>The consumer-supplied order is authoritative and deterministic: documents render first, in
 * list order, followed by child sections in list order. Sections drive the table of contents,
 * bookmarks, and optional cover sheets.
 */
@JsonDeserialize(builder = BundleSection.Builder.class)
public final class BundleSection {

  @JsonProperty
  private final String title;

  @JsonProperty
  private final List<BundleDocument> documents;

  @JsonProperty
  private final List<BundleSection> sections;

  @JsonProperty
  private final EmptySectionPolicy emptySectionPolicy;

  private BundleSection(Builder builder) {
    this.title = builder.title;
    this.documents = List.copyOf(builder.documents);
    this.sections = List.copyOf(builder.sections);
    this.emptySectionPolicy = builder.emptySectionPolicy;
  }

  /** Starts building a section with the given title. */
  public static Builder builder(String title) {
    return new Builder(Validate.requireNonBlank(title, "BundleSection.title"));
  }

  /** The section title. */
  public String title() {
    return title;
  }

  /** The documents in this section, in render order. */
  public List<BundleDocument> documents() {
    return documents;
  }

  /** The child sections, rendered after this section's documents, in order. */
  public List<BundleSection> sections() {
    return sections;
  }

  /** What to render if this section is empty at generation time. */
  public EmptySectionPolicy emptySectionPolicy() {
    return emptySectionPolicy;
  }

  /**
   * Builder for {@link BundleSection}.
   */
  @JsonPOJOBuilder(withPrefix = "")
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Builder {

    private final String title;
    private final List<BundleDocument> documents = new ArrayList<>();
    private final List<BundleSection> sections = new ArrayList<>();
    private EmptySectionPolicy emptySectionPolicy = EmptySectionPolicy.OMIT;

    @JsonCreator
    private Builder(@JsonProperty("title") String title) {
      // The JSON path must enforce the same invariant as the builder(title) factory, so a
      // tampered stored request cannot smuggle in a blank section title.
      this.title = Validate.requireNonBlank(title, "BundleSection.title");
    }

    /** Appends one document. */
    public Builder document(BundleDocument document) {
      documents.add(Validate.requireNonNull(document, "BundleSection document"));
      return this;
    }

    /** Appends documents in list order. */
    public Builder documents(List<BundleDocument> documents) {
      Validate.requireNonNull(documents, "BundleSection.documents").forEach(this::document);
      return this;
    }

    /** Appends one child section. */
    public Builder section(BundleSection section) {
      sections.add(Validate.requireNonNull(section, "BundleSection child section"));
      return this;
    }

    /** Appends child sections in list order. */
    public Builder sections(List<BundleSection> sections) {
      Validate.requireNonNull(sections, "BundleSection.sections").forEach(this::section);
      return this;
    }

    /**
     * Sets what to render if the section is empty at generation time. Defaults to
     * {@link EmptySectionPolicy#OMIT}.
     */
    public Builder emptySectionPolicy(EmptySectionPolicy policy) {
      this.emptySectionPolicy = Validate.requireNonNull(policy, "BundleSection.emptySectionPolicy");
      return this;
    }

    /** Builds the immutable section. */
    public BundleSection build() {
      return new BundleSection(this);
    }
  }
}
