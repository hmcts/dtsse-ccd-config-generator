package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Consumer-supplied context that accompanies one bundle execution and is passed through to the
 * {@link DocumentResolver} port.
 *
 * <p>The context must never carry secrets: no bearer tokens, service tokens, or signed URLs. A
 * background resolver uses the consuming service's own system-user access path, not material
 * captured here. Everything in the context must be safe to log.
 */
@JsonDeserialize(builder = BundleExecutionContext.Builder.class)
public final class BundleExecutionContext {

  private static final BundleExecutionContext EMPTY = builder().build();

  @JsonProperty
  private final String caseReference;

  @JsonProperty
  private final String initiator;

  private final Map<String, String> attributes;

  private BundleExecutionContext(Builder builder) {
    this.caseReference = builder.caseReference;
    this.initiator = builder.initiator;
    this.attributes = Map.copyOf(builder.attributes);
  }

  /** Starts building an execution context. */
  public static Builder builder() {
    return new Builder();
  }

  /** An empty context, for executions that need no consumer context. */
  public static BundleExecutionContext empty() {
    return EMPTY;
  }

  /**
   * The case reference this bundle belongs to, used for correlation and by resolvers that need
   * case scope.
   */
  public Optional<String> caseReference() {
    return Optional.ofNullable(caseReference);
  }

  /**
   * A non-secret reference to who or what initiated the bundle, recorded in the generation
   * report.
   */
  public Optional<String> initiator() {
    return Optional.ofNullable(initiator);
  }

  /** Additional non-secret, log-safe attributes for consumer adapters. */
  @JsonAnyGetter
  public Map<String, String> attributes() {
    return attributes;
  }

  /**
   * Builder for {@link BundleExecutionContext}.
   */
  @JsonPOJOBuilder(withPrefix = "")
  public static final class Builder {

    private String caseReference;
    private String initiator;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    private Builder() {
    }

    /** Sets the case reference this bundle belongs to. */
    public Builder caseReference(String caseReference) {
      this.caseReference = caseReference;
      return this;
    }

    /** Sets a non-secret reference to who or what initiated the bundle. */
    public Builder initiator(String initiator) {
      this.initiator = initiator;
      return this;
    }

    /**
     * Adds one non-secret, log-safe attribute. The keys {@code caseReference} and {@code initiator}
     * are reserved: attributes share the declared properties' JSON object when a durable job persists
     * the context, so use the dedicated builder methods for those values.
     */
    @JsonAnySetter
    public Builder attribute(String key, String value) {
      Validate.requireNonBlank(key, "BundleExecutionContext attribute key");
      if ("caseReference".equals(key) || "initiator".equals(key)) {
        throw new IllegalArgumentException(
            "BundleExecutionContext attribute key '" + key + "' is reserved for the declared "
                + "property of the same name; set it with the " + key + "(...) builder method instead");
      }
      attributes.put(key,
          Validate.requireNonNull(value, "BundleExecutionContext attribute value"));
      return this;
    }

    /** Builds the immutable context. */
    public BundleExecutionContext build() {
      return new BundleExecutionContext(this);
    }
  }
}
