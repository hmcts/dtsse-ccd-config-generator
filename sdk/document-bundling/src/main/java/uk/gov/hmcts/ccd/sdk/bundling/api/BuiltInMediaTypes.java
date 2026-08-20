package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Set;

/**
 * The media types the SDK handles by default, grouped by built-in handler. The image and office
 * lists are verified against {@code em-stitching-api}'s {@code ImageConverter} and
 * {@code DocmosisConverter} so default coverage matches the current microservice.
 */
public final class BuiltInMediaTypes {

  /** Validated and passed through unchanged. */
  public static final String PDF = "application/pdf";

  /**
   * Rendered onto a correctly sized PDF page. Includes the non-standard {@code image/jpg} alias
   * that document-store metadata carries in the wild, matching the current microservice.
   */
  public static final Set<String> IMAGES = Set.of(
      "image/png",
      "image/jpeg",
      "image/jpg",
      "image/tiff",
      "image/bmp",
      "image/gif",
      "image/svg+xml");

  /**
   * Converted via the shared Docmosis render service, registered only when Docmosis is
   * configured. This is {@code DocmosisConverter}'s accept list minus
   * {@code application/octet-stream}: blindly routing untyped content to Docmosis is a documented
   * defect of the current service that this module deliberately does not replicate — untyped
   * content is media-type-detected from content instead.
   */
  public static final Set<String> OFFICE = Set.of(
      "application/msword",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/x-tika-ooxml",
      "application/x-tika-msoffice",
      "application/vnd.ms-excel",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
      "application/vnd.ms-powerpoint",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "application/vnd.openxmlformats-officedocument.presentationml.template",
      "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
      "application/rtf",
      "text/plain");

  /** Represented by a generated, accessible media link page built from supplied metadata. */
  public static final Set<String> MEDIA = Set.of("audio/mpeg", "video/mp4");

  private BuiltInMediaTypes() {
  }
}
