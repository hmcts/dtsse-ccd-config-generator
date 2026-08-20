package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The effective per-media-type handler registry, built once when the renderer is constructed and
 * inspectable from then on.
 *
 * <p>Built-in handlers register first; extensions apply in registration order, so the last
 * registration for a media type wins. Media types are normalised to lower case for registration
 * and lookup. A media type with no handler fails the bundle with an error naming the type, the
 * document, and the registered types.
 */
public final class HandlerRegistry {

  private final Map<String, DocumentHandler> handlers;

  private HandlerRegistry(Map<String, DocumentHandler> handlers) {
    this.handlers = Collections.unmodifiableMap(handlers);
  }

  /**
   * Builds the effective registry from the built-in registrations and the consumer's extensions,
   * applied in order.
   *
   * @param builtIns the built-in handler registrations
   * @param extensions the consumer extensions, in registration order
   * @return the effective registry
   */
  public static HandlerRegistry create(
      Map<String, DocumentHandler> builtIns, List<BundlingExtension> extensions) {
    Map<String, DocumentHandler> effective = new LinkedHashMap<>();
    Validate.requireNonNull(builtIns, "HandlerRegistry builtIns")
        .forEach((type, handler) -> effective.put(
            normalise(type), Validate.requireNonNull(handler, "built-in handler for " + type)));
    for (BundlingExtension extension : Validate.requireNonNull(extensions, "extensions")) {
      extension.configure(new MutationContext(extension, effective));
    }
    return new HandlerRegistry(effective);
  }

  /**
   * Looks up the handler for a media type.
   *
   * @param mediaType the media type, normalised before lookup
   * @return the handler, or empty when the type is unhandled
   */
  public Optional<DocumentHandler> handlerFor(String mediaType) {
    return Optional.ofNullable(handlers.get(normalise(mediaType)));
  }

  /**
   * The media types the registry currently handles.
   *
   * @return the immutable set of handled media types
   */
  public Set<String> handledMediaTypes() {
    return handlers.keySet();
  }

  private static String normalise(String mediaType) {
    return Validate.requireNonBlank(mediaType, "mediaType").trim().toLowerCase(Locale.ROOT);
  }

  private record MutationContext(BundlingExtension extension, Map<String, DocumentHandler> effective)
      implements BundlingExtensionContext {

    @Override
    public void addHandler(String mediaType, DocumentHandler handler) {
      String type = normalise(mediaType);
      Validate.requireNonNull(handler, "handler for " + type);
      if (effective.containsKey(type)) {
        throw new IllegalStateException(
            "Extension '" + extension.name() + "' cannot add a handler for '" + type
                + "': the type is already handled. Use replaceHandler to override it.");
      }
      effective.put(type, handler);
    }

    @Override
    public void replaceHandler(String mediaType, DocumentHandler handler) {
      String type = normalise(mediaType);
      Validate.requireNonNull(handler, "handler for " + type);
      if (!effective.containsKey(type)) {
        throw new IllegalStateException(
            "Extension '" + extension.name() + "' cannot replace the handler for '" + type
                + "': the type is not handled. Use addHandler to add support for it.");
      }
      effective.put(type, handler);
    }

    @Override
    public void removeHandler(String mediaType) {
      String type = normalise(mediaType);
      if (effective.remove(type) == null) {
        throw new IllegalStateException(
            "Extension '" + extension.name() + "' cannot remove the handler for '" + type
                + "': the type is not handled.");
      }
    }
  }
}
