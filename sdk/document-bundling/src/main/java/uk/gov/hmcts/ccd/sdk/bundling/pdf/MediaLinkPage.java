package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;

public record MediaLinkPage(String mediaType, MediaPlaceholder placeholder)
    implements AssemblyContent {
  public MediaLinkPage {
    Checks.requireNonBlank(mediaType, "MediaLinkPage.mediaType");
    Checks.requireNonNull(placeholder, "MediaLinkPage.placeholder");
  }
}
