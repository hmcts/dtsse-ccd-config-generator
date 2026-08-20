package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Registry mutations available to a {@link BundlingExtension}.
 *
 * <p>{@code addHandler} and {@code replaceHandler} are distinct on purpose: silently shadowing a
 * built-in handler — or silently failing to — is a classic source of surprise, so each fails fast
 * with a message naming the extension and media type involved.
 */
public interface BundlingExtensionContext {

  /**
   * Adds support for a media type the registry does not yet handle.
   *
   * @param mediaType the media type to handle
   * @param handler the handler to register
   * @throws IllegalStateException if the type already has a handler
   */
  void addHandler(String mediaType, DocumentHandler handler);

  /**
   * Overrides the existing handler for a media type.
   *
   * @param mediaType the media type to override
   * @param handler the replacement handler
   * @throws IllegalStateException if the type has no handler to replace
   */
  void replaceHandler(String mediaType, DocumentHandler handler);

  /**
   * Removes the handler for a media type, reverting it to unhandled: a bundle containing a
   * document of that type fails with a descriptive error.
   *
   * @param mediaType the media type to remove
   * @throws IllegalStateException if the type has no handler to remove
   */
  void removeHandler(String mediaType);
}
