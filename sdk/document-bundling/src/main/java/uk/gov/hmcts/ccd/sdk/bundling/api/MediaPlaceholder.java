package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Metadata for an audio or video document that is represented in the bundle by a generated link
 * page rather than by its content.
 *
 * <p>The media file itself is never fetched by default: the page is built entirely from this
 * metadata, so a large recording costs nothing to bundle. The consumer must supply the access URL;
 * the SDK cannot know how a service exposes media for playback and never invents links from
 * {@link DocumentReference} internals. Link freshness is a consumer decision — a signed URL that
 * expires before the bundle is read is worse than a stable case-scoped link.
 */
@JsonDeserialize(builder = MediaPlaceholder.Builder.class)
public final class MediaPlaceholder {

  private static final int MAX_NOTE_LENGTH = 500;

  @JsonProperty
  private final String accessUrl;

  @JsonProperty
  private final String mediaType;

  @JsonProperty
  private final Duration duration;

  @JsonProperty
  private final String note;

  private MediaPlaceholder(Builder builder) {
    this.accessUrl = builder.accessUrl;
    this.mediaType = builder.mediaType;
    this.duration = builder.duration;
    this.note = builder.note;
  }

  /**
   * Starts building a media placeholder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The URL where a reader can play or download the media.
   *
   * @return the consumer-supplied access URL
   */
  public String accessUrl() {
    return accessUrl;
  }

  /**
   * The media type of the recording, for example {@code audio/mpeg} or {@code video/mp4}.
   *
   * <p>Because the media file is never fetched, this declaration is the only way the renderer
   * can route the document to its per-media-type handler and render the type on the generated
   * link page. Request validation requires it and checks it against the registered handlers
   * before any content is read.
   *
   * @return the declared media type, or empty when the placeholder predates the field
   */
  public Optional<String> mediaType() {
    return Optional.ofNullable(mediaType);
  }

  /**
   * The duration of the recording, if supplied, rendered on the link page.
   *
   * @return the optional duration
   */
  public Optional<Duration> duration() {
    return Optional.ofNullable(duration);
  }

  /**
   * A short note rendered on the link page, for example access instructions.
   *
   * @return the optional note
   */
  public Optional<String> note() {
    return Optional.ofNullable(note);
  }

  /**
   * Builder for {@link MediaPlaceholder}.
   */
  @JsonPOJOBuilder(withPrefix = "")
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Builder {

    private String accessUrl;
    private String mediaType;
    private Duration duration;
    private String note;

    private Builder() {
    }

    /**
     * Sets the URL where a reader can play or download the media. Required; must be an absolute
     * URL.
     *
     * @param accessUrl the access URL
     * @return this builder
     */
    public Builder accessUrl(String accessUrl) {
      this.accessUrl = accessUrl;
      return this;
    }

    /**
     * Sets the media type of the recording, for example {@code audio/mpeg} or {@code video/mp4}.
     * Required by request validation before rendering: the file is never fetched, so this
     * declaration is what routes the document to its handler.
     *
     * @param mediaType the declared media type
     * @return this builder
     */
    public Builder mediaType(String mediaType) {
      this.mediaType = mediaType;
      return this;
    }

    /**
     * Sets the duration of the recording, rendered on the link page.
     *
     * @param duration the media duration
     * @return this builder
     */
    public Builder duration(Duration duration) {
      this.duration = duration;
      return this;
    }

    /**
     * Sets a short note rendered on the link page. Limited to {@value #MAX_NOTE_LENGTH}
     * characters.
     *
     * @param note the note text
     * @return this builder
     */
    public Builder note(String note) {
      this.note = note;
      return this;
    }

    /**
     * Builds the placeholder, validating the access URL.
     *
     * @return the immutable placeholder
     */
    public MediaPlaceholder build() {
      Validate.requireNonBlank(accessUrl, "MediaPlaceholder.accessUrl");
      URI parsed;
      try {
        parsed = URI.create(accessUrl);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "MediaPlaceholder.accessUrl is not a valid URL: '" + accessUrl + "'", e);
      }
      if (!parsed.isAbsolute()) {
        throw new IllegalArgumentException(
            "MediaPlaceholder.accessUrl must be an absolute URL: '" + accessUrl + "'");
      }
      if (note != null && note.length() > MAX_NOTE_LENGTH) {
        throw new IllegalArgumentException(
            "MediaPlaceholder.note must not exceed " + MAX_NOTE_LENGTH + " characters");
      }
      return new MediaPlaceholder(this);
    }
  }
}
