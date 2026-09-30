package uk.gov.hmcts.ccd.sdk.api.external;

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

  private final Function<Class<?>, Object> reader;

  private ClientContext(Function<Class<?>, Object> reader) {
    this.reader = reader;
  }

  /** The frontend sent no context. */
  public static ClientContext none() {
    return NONE;
  }

  /** A context holding this value, as a unit test of a start handler gives it. */
  public static ClientContext of(Object value) {
    Objects.requireNonNull(value);
    return new ClientContext(type -> type.cast(value));
  }

  /** A context the SDK reads from the frontend's JSON as whatever type the handler asks for. */
  public static ClientContext reading(Function<Class<?>, Object> reader) {
    return new ClientContext(Objects.requireNonNull(reader));
  }

  /**
   * The context read as this type, leaving out anything the type does not name, or empty when the
   * frontend sent none. Throws IllegalArgumentException if the context cannot be read as the type.
   */
  public <T> Optional<T> as(Class<T> type) {
    return Optional.ofNullable(type.cast(reader.apply(type)));
  }
}
