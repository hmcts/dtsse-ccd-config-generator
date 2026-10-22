package uk.gov.hmcts.ccd.sdk.conversion;

import java.util.Map;
import java.util.Set;

/**
 * The media types {@link DocumentConverter} converts, grouped by how they are converted.
 *
 * <p>Media types are compared after normalisation (lower case, parameters stripped). The office
 * list covers every Word, Excel and PowerPoint variant the Possessions service accepts as
 * supporting evidence, including templates, add-ins, slideshows and binary workbooks.
 */
public final class SupportedMediaTypes {

  /** Validated and passed through, or re-rendered for print. */
  public static final String PDF = "application/pdf";

  /** Decoded and drawn onto PDF pages; a multi-page TIFF becomes one page per frame. */
  public static final Set<String> IMAGES = Set.of(
      "image/png",
      "image/jpeg",
      "image/jpg",
      "image/tiff",
      "image/bmp",
      "image/gif");

  /** Converted by the shared Docmosis service; needs a configured Docmosis client. */
  public static final Set<String> OFFICE = Set.of(
      "application/msword",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
      "application/x-tika-ooxml",
      "application/x-tika-msoffice",
      "application/vnd.ms-excel",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
      "application/vnd.ms-excel.sheet.binary.macroenabled.12",
      "application/vnd.ms-powerpoint",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "application/vnd.openxmlformats-officedocument.presentationml.template",
      "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
      "application/rtf",
      "text/plain",
      "text/csv");

  /**
   * Non-standard names document stores use, mapped to the name this module routes by. Applied
   * before content detection so an alias is not reported as a mismatch.
   */
  static final Map<String, String> ALIASES = Map.of(
      "text/rtf", "application/rtf",
      "image/tif", "image/tiff",
      "image/x-ms-bmp", "image/bmp",
      "image/x-bmp", "image/bmp",
      "image/pjpeg", "image/jpeg",
      "application/csv", "text/csv",
      "text/comma-separated-values", "text/csv");

  private SupportedMediaTypes() {
  }
}
