package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Registry mutations available to a {@link BundlingExtension}.
 *
 * <p>{@code addHandler} and {@code replaceHandler} are distinct on purpose: silently shadowing a
 * built-in handler — or silently failing to — is a classic source of surprise, so each fails fast
 * with a message naming the extension and media type involved.
 */
public interface BundlingExtensionContext {

  /** Adds support for a media type the registry does not yet handle. */
  void addHandler(String mediaType, DocumentHandler handler);

  /** Overrides the existing handler for a media type. */
  void replaceHandler(String mediaType, DocumentHandler handler);

  /**
   * Removes the handler for a media type, reverting it to unhandled: a bundle containing a
   * document of that type fails with a descriptive error.
   */
  void removeHandler(String mediaType);
}
