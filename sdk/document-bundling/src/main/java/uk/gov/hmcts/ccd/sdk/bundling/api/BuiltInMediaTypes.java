package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Set;
import uk.gov.hmcts.ccd.sdk.conversion.SupportedMediaTypes;

/**
 * The media types the SDK handles by default, grouped by built-in handler. PDF, images and office
 * formats are document-conversion's {@link SupportedMediaTypes}, so bundling and conversion
 * accept the same types. They cover {@code em-stitching-api}'s {@code ImageConverter} and
 * {@code DocmosisConverter} lists, plus the office templates, binary workbooks and CSV that
 * Docmosis also converts.
 */
public final class BuiltInMediaTypes {

  /** Validated and passed through unchanged. */
  public static final String PDF = SupportedMediaTypes.PDF;

  /**
   * Rendered onto a correctly sized PDF page. Includes the non-standard {@code image/jpg} alias
   * that document-store metadata carries in the wild, matching the current microservice.
   */
  public static final Set<String> IMAGES = SupportedMediaTypes.IMAGES;

  /**
   * Converted via the shared Docmosis render service, registered only when Docmosis is
   * configured. Unlike em-stitching's {@code DocmosisConverter}, this excludes
   * {@code application/octet-stream}: blindly routing untyped content to Docmosis is a documented
   * defect of the current service that this module deliberately does not replicate — untyped
   * content is media-type-detected from content instead.
   */
  public static final Set<String> OFFICE = SupportedMediaTypes.OFFICE;

  /** Represented by a generated, accessible media link page built from supplied metadata. */
  public static final Set<String> MEDIA = Set.of("audio/mpeg", "video/mp4");

  private BuiltInMediaTypes() {
  }
}
