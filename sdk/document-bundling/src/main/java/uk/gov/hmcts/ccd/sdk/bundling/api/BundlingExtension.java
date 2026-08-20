package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * A named extension module that adds or overrides per-media-type behaviour, following the Jackson
 * {@code Module} pattern: a renderer built with no extensions reproduces the output of the
 * current stitching microservice.
 *
 * <p>Extensions apply in registration order after the built-in handlers, so the last registration
 * for a media type wins. Extensions change how a source becomes PDF pages; presentation remains
 * preset-based and is not part of this SPI.
 */
public interface BundlingExtension {

  /**
   * The extension's name, used in registry error messages and logs.
   *
   * @return the extension name
   */
  String name();

  /**
   * Registers this extension's handlers.
   *
   * @param context the registry mutation operations available to this extension
   */
  void configure(BundlingExtensionContext context);
}
