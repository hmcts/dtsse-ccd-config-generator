package uk.gov.hmcts.ccd.sdk.api.external;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a frontend tells an external event's start that the case cannot, such as which record the
 * user chose to act on. The frontend sends it as JSON in the Client-Context header, which CCD passes
 * on to the start; the start handler reads it as the type it expects.
 */
public final class ClientContext {

  private static final ClientContext NONE = new ClientContext(type -> null);
  // Reads a value given to of(...) as the runtime reads the frontend's JSON.
  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final Function<Class<?>, Object> reader;

  private ClientContext(Function<Class<?>, Object> reader) {
    this.reader = reader;
  }

  /** The frontend sent no context. */
  public static ClientContext none() {
    return NONE;
  }

  /**
   * A context holding this value, as a unit test of a start handler gives it. The handler reads it
   * as it would the JSON a frontend sends, so it need not be the type the handler asks for.
   */
  public static ClientContext of(Object value) {
    Objects.requireNonNull(value);
    return new ClientContext(type -> MAPPER.convertValue(value, type));
  }

  /**
   * A context the SDK reads from the frontend's JSON as whatever type the handler asks for. The
   * reader throws IllegalArgumentException for a type the context cannot be read as.
   */
  public static ClientContext reading(Function<Class<?>, Object> reader) {
    return new ClientContext(Objects.requireNonNull(reader));
  }

  /**
   * The context read as this type, leaving out anything the type does not name, or empty when the
   * frontend sent none. A context that cannot be read as the type is the frontend's mistake, and
   * rejects the start as throwing {@link ExternalRejection#because} does.
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> as(Class<T> type) {
    try {
      // Not type.cast, which refuses the boxed value read for a primitive type.
      return Optional.ofNullable((T) reader.apply(type));
    } catch (IllegalArgumentException e) {
      throw ExternalRejection.because("The client context is not a valid " + type.getSimpleName());
    }
  }
}
