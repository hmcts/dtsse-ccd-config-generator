package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A complete, explicit description of one bundle: an ordered tree of sections and documents plus
 * presentation choices.
 *
 * <p>The request is storage-agnostic — documents are opaque {@link DocumentReference}s — and
 * workflow-agnostic: the same request renders synchronously through a
 * {@code BundleRenderer} or durably through a {@code BundleJobService}. The consumer-minted
 * {@link #externalId()} is the idempotency key for durable execution.
 */
@JsonDeserialize(builder = BundleRequest.Builder.class)
public final class BundleRequest {

  @JsonProperty
  private final UUID externalId;

  @JsonProperty
  private final String title;

  @JsonProperty
  private final String fileName;

  @JsonProperty
  private final BundleSection root;

  @JsonProperty
  private final BundlePresentation presentation;

  private BundleRequest(Builder builder) {
    this.externalId = builder.externalId;
    this.title = builder.title;
    this.fileName = builder.fileName;
    this.root = builder.root;
    this.presentation = builder.presentation;
  }

  /**
   * Starts building a bundle request.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The consumer-minted UUID identifying this bundle request; the idempotency key for durable
   * execution. Whether a new bundle replaces, versions, or coexists with a previous one is the
   * service team's decision, expressed through the ids it mints.
   *
   * @return the external id
   */
  public UUID externalId() {
    return externalId;
  }

  /**
   * The bundle title, rendered on the generated title page.
   *
   * @return the bundle title
   */
  public String title() {
    return title;
  }

  /**
   * The output PDF file name.
   *
   * @return the output file name
   */
  public String fileName() {
    return fileName;
  }

  /**
   * The root of the ordered section/document tree.
   *
   * @return the root section
   */
  public BundleSection root() {
    return root;
  }

  /**
   * The presentation preset for the generated bundle.
   *
   * @return the presentation
   */
  public BundlePresentation presentation() {
    return presentation;
  }

  /**
   * All documents in the tree, in deterministic render order.
   *
   * @return the ordered document list
   */
  public List<BundleDocument> allDocuments() {
    List<BundleDocument> documents = new ArrayList<>();
    collectDocuments(root, documents);
    return List.copyOf(documents);
  }

  private static void collectDocuments(BundleSection section, List<BundleDocument> into) {
    into.addAll(section.documents());
    for (BundleSection child : section.sections()) {
      collectDocuments(child, into);
    }
  }

  private static boolean hasPlaceholderSection(BundleSection section) {
    if (section.emptySectionPolicy() == EmptySectionPolicy.INCLUDE_PLACEHOLDER) {
      return true;
    }
    return section.sections().stream().anyMatch(BundleRequest::hasPlaceholderSection);
  }

  /**
   * Builder for {@link BundleRequest}.
   */
  @JsonPOJOBuilder(withPrefix = "")
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Builder {

    private UUID externalId;
    private String title;
    private String fileName;
    private BundleSection root;
    private BundlePresentation presentation = BundlePresentation.courtDefault();

    private Builder() {
    }

    /**
     * Sets the consumer-minted UUID that identifies this bundle request. Required.
     *
     * @param externalId the external id
     * @return this builder
     */
    public Builder externalId(UUID externalId) {
      this.externalId = externalId;
      return this;
    }

    /**
     * Sets the bundle title. Required.
     *
     * @param title the bundle title
     * @return this builder
     */
    public Builder title(String title) {
      this.title = title;
      return this;
    }

    /**
     * Sets the output PDF file name. Required; must end with {@code .pdf} and contain no path
     * separators or control characters.
     *
     * @param fileName the output file name
     * @return this builder
     */
    public Builder fileName(String fileName) {
      this.fileName = fileName;
      return this;
    }

    /**
     * Sets the root of the ordered section/document tree. Required.
     *
     * @param root the root section
     * @return this builder
     */
    public Builder root(BundleSection root) {
      this.root = root;
      return this;
    }

    /**
     * Sets the presentation preset. Defaults to {@link BundlePresentation#courtDefault()}.
     *
     * @param presentation the presentation preset
     * @return this builder
     */
    public Builder presentation(BundlePresentation presentation) {
      this.presentation = Validate.requireNonNull(presentation, "BundleRequest.presentation");
      return this;
    }

    /**
     * Builds the request, validating required fields, the output file name, document id
     * uniqueness across the whole tree, and that the bundle has content.
     *
     * @return the immutable request
     */
    public BundleRequest build() {
      Validate.requireNonNull(externalId, "BundleRequest.externalId");
      Validate.requireNonBlank(title, "BundleRequest.title");
      validateFileName(fileName);
      Validate.requireNonNull(root, "BundleRequest.root");

      BundleRequest request = new BundleRequest(this);
      List<BundleDocument> documents = request.allDocuments();
      validateUniqueIds(documents);
      if (documents.isEmpty() && !hasPlaceholderSection(root)) {
        throw new IllegalArgumentException(
            "A bundle request must contain at least one document or a section whose "
                + "emptySectionPolicy is INCLUDE_PLACEHOLDER");
      }
      return request;
    }

    private static void validateFileName(String fileName) {
      Validate.requireNonBlank(fileName, "BundleRequest.fileName");
      boolean unsafe = fileName.contains("/")
          || fileName.contains("\\")
          || fileName.chars().anyMatch(c -> c < 0x20);
      if (unsafe) {
        throw new IllegalArgumentException(
            "BundleRequest.fileName must not contain path separators or control characters: '"
                + fileName + "'");
      }
      if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
        throw new IllegalArgumentException(
            "BundleRequest.fileName must end with .pdf: '" + fileName + "'");
      }
    }

    private static void validateUniqueIds(List<BundleDocument> documents) {
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (BundleDocument document : documents) {
        counts.merge(document.id(), 1, Integer::sum);
      }
      List<String> duplicates = counts.entrySet().stream()
          .filter(entry -> entry.getValue() > 1)
          .map(Map.Entry::getKey)
          .toList();
      if (!duplicates.isEmpty()) {
        throw new IllegalArgumentException(
            "Document ids must be unique within a bundle request; duplicates: " + duplicates);
      }
    }
  }
}
