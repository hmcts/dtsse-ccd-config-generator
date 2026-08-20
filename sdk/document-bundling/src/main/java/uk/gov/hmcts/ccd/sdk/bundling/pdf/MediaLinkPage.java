package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;

/**
 * A generated single-page link page for an audio or video document. The page is a deterministic
 * PDFBox template showing the item's title and date, the media type, the optional duration and
 * note, and a clickable absolute link to the consumer-supplied access URL. It participates in the
 * table of contents, bookmarks and pagination like any other document.
 *
 * @param mediaType the media type rendered on the page, for example {@code audio/mpeg}
 * @param placeholder the consumer-supplied access URL and optional duration and note
 */
public record MediaLinkPage(String mediaType, MediaPlaceholder placeholder)
    implements AssemblyContent {

  /**
   * Validates the spec.
   *
   * @param mediaType the media type rendered on the page
   * @param placeholder the consumer-supplied access URL and optional duration and note
   */
  public MediaLinkPage {
    Checks.requireNonBlank(mediaType, "MediaLinkPage.mediaType");
    Checks.requireNonNull(placeholder, "MediaLinkPage.placeholder");
  }
}
