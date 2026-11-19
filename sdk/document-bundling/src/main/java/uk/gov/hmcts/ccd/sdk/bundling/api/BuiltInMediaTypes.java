package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.Set;

/**
 * The media types bundling handles itself rather than converting. Every other type is converted
 * to PDF by document-conversion's {@code DocumentConverter}; to bundle a new file type, register
 * a {@code PdfConverter} with it.
 */
public final class BuiltInMediaTypes {

  /**
   * Audio and video, represented by a generated, accessible link page built from the document's
   * {@link MediaPlaceholder}. The recording itself is never fetched.
   */
  public static final Set<String> MEDIA = Set.of("audio/mpeg", "video/mp4");

  private BuiltInMediaTypes() {
  }
}
