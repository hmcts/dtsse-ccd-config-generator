package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.time.LocalDate;
import java.util.Optional;

/**
 * One document in a bundle: immutable display metadata plus the opaque reference a
 * {@link DocumentResolver} turns into content.
 *
 * <p>A document carrying a {@link MediaPlaceholder} is rendered as a generated media link page;
 * every other document's content is resolved and converted to PDF pages.
 */
@JsonDeserialize(builder = BundleDocument.Builder.class)
public final class BundleDocument {

  @JsonProperty
  private final String id;

  @JsonProperty
  private final String title;

  @JsonProperty
  private final LocalDate date;

  @JsonProperty
  private final DocumentReference reference;

  @JsonProperty
  private final boolean confidential;

  @JsonProperty
  private final MediaPlaceholder media;

  private BundleDocument(Builder builder) {
    this.id = builder.id;
    this.title = builder.title;
    this.date = builder.date;
    this.reference = builder.reference;
    this.confidential = builder.confidential;
    this.media = builder.media;
  }

  /** Starts building a bundle document. */
  public static Builder builder() {
    return new Builder();
  }

  /** The consumer-supplied identifier, unique within one bundle request. */
  public String id() {
    return id;
  }

  /** The display title used in the table of contents, bookmarks, and cover sheets. */
  public String title() {
    return title;
  }

  /** The document date shown in the table of contents, if supplied. */
  public Optional<LocalDate> date() {
    return Optional.ofNullable(date);
  }

  /**
   * The opaque reference resolved by the consumer's {@link DocumentResolver}.
   */
  public DocumentReference reference() {
    return reference;
  }

  /**
   * Whether the document is marked confidential, driving the configured
   * {@link ConfidentialMarking} and contents markers.
   */
  public boolean confidential() {
    return confidential;
  }

  /**
   * The media placeholder metadata, present when this document is an audio or video item
   * represented by a generated link page.
   */
  public Optional<MediaPlaceholder> media() {
    return Optional.ofNullable(media);
  }

  /**
   * Builder for {@link BundleDocument}.
   */
  @JsonPOJOBuilder(withPrefix = "")
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Builder {

    private String id;
    private String title;
    private LocalDate date;
    private DocumentReference reference;
    private boolean confidential;
    private MediaPlaceholder media;

    private Builder() {
    }

    /** Sets the identifier, unique within one bundle request. Required. */
    public Builder id(String id) {
      this.id = id;
      return this;
    }

    /** Sets the display title. Required. */
    public Builder title(String title) {
      this.title = title;
      return this;
    }

    /** Sets the document date shown in the table of contents. */
    public Builder date(LocalDate date) {
      this.date = date;
      return this;
    }

    /**
     * Sets the opaque reference resolved by the consumer's {@link DocumentResolver}. Required.
     */
    public Builder reference(DocumentReference reference) {
      this.reference = reference;
      return this;
    }

    /** Marks the document confidential. */
    public Builder confidential(boolean confidential) {
      this.confidential = confidential;
      return this;
    }

    /**
     * Supplies media placeholder metadata, marking this document as an audio or video item
     * rendered as a generated link page.
     */
    public Builder media(MediaPlaceholder media) {
      this.media = media;
      return this;
    }

    /** Builds the document, validating required fields. */
    public BundleDocument build() {
      Validate.requireNonBlank(id, "BundleDocument.id");
      Validate.requireNonBlank(title, "BundleDocument.title");
      Validate.requireNonNull(reference, "BundleDocument.reference");
      return new BundleDocument(this);
    }
  }
}
